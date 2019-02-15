/**
 * Copyright (C) 2014 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.ebms3.submit.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.holodeckb2b.common.messagemodel.Payload;
import org.holodeckb2b.common.messagemodel.PullRequest;
import org.holodeckb2b.common.messagemodel.UserMessage;
import org.holodeckb2b.common.util.Utils;
import org.holodeckb2b.core.validation.ValidationResult;
import org.holodeckb2b.interfaces.core.HolodeckB2BCoreInterface;
import org.holodeckb2b.interfaces.customvalidation.IMessageValidationSpecification;
import org.holodeckb2b.interfaces.customvalidation.MessageValidationException;
import org.holodeckb2b.interfaces.general.EbMSConstants;
import org.holodeckb2b.interfaces.messagemodel.IPayload;
import org.holodeckb2b.interfaces.messagemodel.IPullRequest;
import org.holodeckb2b.interfaces.messagemodel.IUserMessage;
import org.holodeckb2b.interfaces.persistency.PersistenceException;
import org.holodeckb2b.interfaces.persistency.entities.IPullRequestEntity;
import org.holodeckb2b.interfaces.persistency.entities.IUserMessageEntity;
import org.holodeckb2b.interfaces.pmode.ILeg;
import org.holodeckb2b.interfaces.pmode.IPMode;
import org.holodeckb2b.interfaces.pmode.IPullRequestFlow;
import org.holodeckb2b.interfaces.processingmodel.ProcessingState;
import org.holodeckb2b.interfaces.submit.IMessageSubmitter;
import org.holodeckb2b.interfaces.submit.MessageSubmitException;
import org.holodeckb2b.module.HolodeckB2BCore;
import org.holodeckb2b.pmode.PModeUtils;

/**
 * Is the default implementation of {@see IMessageSubmitter}.
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
public class MessageSubmitter implements IMessageSubmitter {

    private static final Log log = LogFactory.getLog(MessageSubmitter.class.getName());

	/**
	 * {@inheritDoc} 
	 */
    @Override
    public String submitMessage(final IUserMessage submission, final boolean movePayloads) throws MessageSubmitException {
        log.debug("Start submission of new User Message");

        try {
            log.trace("Get the P-Mode for the message");
            final IPMode  pmode = HolodeckB2BCore.getPModeSet().get(submission.getPModeId());

            if (pmode == null) {
                log.warn("No P-Mode found for submitted message, rejecting message!");
                throw new MessageSubmitException("No P-Mode found for message");
            }
            log.debug("Found P-Mode:" + pmode.getId());

            log.trace("Check for completeness: combined with P-Mode all info must be known");
            // The complete operation will throw aMessageSubmitException if meta-data is not complete
            final UserMessage completedMetadata = MMDCompleter.complete(submission, pmode);

            log.trace("Checking availability of payloads");
            checkPayloads(completedMetadata, pmode); // Throws MessageSubmitException if there is a problem with a specified submissionPayloadInfo

            log.trace("Validate submitted message if specified");
            validateMessage(completedMetadata, pmode);

            log.trace("Add message to database");
            final IUserMessageEntity newUserMessage = (IUserMessageEntity)
                                        HolodeckB2BCore.getStorageManager().storeOutGoingMessageUnit(completedMetadata);
            try {
                moveOrCopyPayloads(newUserMessage, movePayloads);
            } catch (final IOException ex) {
                log.error("Could not move/copy payload(s) to the internal storage! Unable to process message!"
                            + "\n\tError details: " + ex.getMessage());
                HolodeckB2BCore.getStorageManager().setProcessingState(newUserMessage, ProcessingState.FAILURE);
                throw new MessageSubmitException("Could not move/copy payload(s) to the internal storage!", ex);
            }

            //Use P-Mode to find out if this message is to be pulled or pushed to receiver
            if (EbMSConstants.ONE_WAY_PULL.equalsIgnoreCase(pmode.getMepBinding())) {
                log.debug("Message is to be pulled by receiver, change ProcessingState to wait for pull");
                HolodeckB2BCore.getStorageManager().setProcessingState(newUserMessage, ProcessingState.AWAITING_PULL);
            } else {
                log.debug("Message is to be pushed to receiver, change ProcessingState to trigger push");
                HolodeckB2BCore.getStorageManager().setProcessingState(newUserMessage, ProcessingState.READY_TO_PUSH);
            }

            log.info("User Message succesfully submitted, messageId=" + newUserMessage.getMessageId());
            return newUserMessage.getMessageId();
        } catch (final PersistenceException dbe) {
            log.error("An error occured when saving user message to database. Details: " + dbe.getMessage());
            throw new MessageSubmitException("Message could not be saved to database", dbe);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String submitMessage(final IPullRequest pullRequest) throws MessageSubmitException {
        log.trace("Start submission of new Pull Request");

        // Check if a valid P-Mode is specified
        final String pmodeId = pullRequest.getPModeId();
        if (Utils.isNullOrEmpty(pmodeId))
            throw new MessageSubmitException("P-Mode Id is missing");
        final IPMode pmode = HolodeckB2BCoreInterface.getPModeSet().get(pmodeId);
        if (pmode == null)
        	throw new MessageSubmitException("No P-Mode with id=" + pmodeId + " configured");
        if (!PModeUtils.doesHolodeckB2BPull(pmode))
        	throw new MessageSubmitException("PMode with id=" + pmodeId + " does not support pulling");
        
        // Check that when a MPC to pull from is available in both request and P-Mode they match or the one in the 
        // request is a sub-channel MPC of the one in the P-Mode
        final IPullRequestFlow pullRequestFlow = PModeUtils.getOutPullRequestFlow(pmode);
        final String pmodeMPC = pullRequestFlow != null ? pullRequestFlow.getMPC() : null;
        String pullMPC = pullRequest.getMPC();
        
        if (Utils.isNullOrEmpty(pullMPC) && Utils.isNullOrEmpty(pmodeMPC))
        	pullMPC = EbMSConstants.DEFAULT_MPC;
        else if (!Utils.isNullOrEmpty(pullMPC) && !Utils.isNullOrEmpty(pmodeMPC) && !pullMPC.startsWith(pmodeMPC))
        	throw new MessageSubmitException("MPC in submission [" + pullMPC + "] conflicts with P-Mode defined MPC ]"
        										+ pmodeMPC + "]");
        else if (Utils.isNullOrEmpty(pullMPC))
        	pullMPC = pmodeMPC;
        
        String prMessageId = null;
        try {
            log.trace("Add PullRequest to database");            
            IPullRequestEntity submittedPR = HolodeckB2BCore.getStorageManager()
            												.storeOutGoingMessageUnit(new PullRequest(pmodeId, pullMPC));
            prMessageId = submittedPR.getMessageId();
            // Indicate that the PR can be directly pushed to other MSH
            HolodeckB2BCore.getStorageManager().setProcessingState(submittedPR, ProcessingState.READY_TO_PUSH);
            log.info("Submitted PullRequest, assigned messageId=" + prMessageId);
        } catch (final PersistenceException ex) {
            log.error("Could not create the PullRequest because a error occurred in the database! Details: "
                        + ex.getMessage());
        }
        return prMessageId;
    }

    /**
     * Helper method to check availability of the submissionPayloadInfo.
     * @todo: Also check compliance with payload profile of PMode!
     *
     * @param um     The meta data on the submitted user message
     * @param pmode  The P-Mode that governs the processing this user message
     * @throws MessageSubmitException When one of the specified submissionPayloadInfo can not be found or when the specified path is
                                is not a regular file
     */
    private void checkPayloads(final IUserMessage um, final IPMode pmode) throws MessageSubmitException {
        final Collection<? extends IPayload> payloads = um.getPayloads();
        if (!Utils.isNullOrEmpty(payloads))
            for(final IPayload p : payloads) {
                // Check that content is available
                if (!checkContent(p))
                    throw new MessageSubmitException("Specified location of payload [uri=" + p.getPayloadURI()
                                + "] content [" + p.getContentLocation()
                                + "] is invalid (non existing or not a regular file!");
                // Check references
                if (!checkPayloadRefs(p, payloads))
                    throw new MessageSubmitException("Specified location of payload [uri=" + p.getPayloadURI()
                                + "] content [" + p.getContentLocation()
                                + "] is invalid (non existing or not a regular file!");
            }
    }

    /**
     * Helper method to check that the specified file for the payload content is available if the payload should be
     * included in the message (containment is either BODY or ATTACHMENT)
     *
     * @param payload   The payload meta-data
     * @return          <code>true</code> when the payload should be contained in the message and a file is available
     *                  at the specified path or when the payload is externally referenced, <br>
     *                  <code>false</code> otherwise
     */
    private boolean checkContent(final IPayload payload) {
        if (payload.getContainment() != IPayload.Containment.EXTERNAL) {
            final String contentLocation = payload.getContentLocation();
            // The location must be specified
            if (Utils.isNullOrEmpty(contentLocation))
                return false;
            else
                // It most point to an existing normal file
                if (Files.isRegularFile(Paths.get(contentLocation)))
                    return true;
                else
                    return false;
        } else
            // For external payload content location is not relevant
            return true;
    }

    /**
     * Helper method to check the URI reference for the payloads to include in the message.
     * <p>The references must be unique within the set of simularly included payloads, so there must be no other payload
     * with the same containment and reference.
     * <p>Payloads that should be included as attachment however don't need to have a reference assigned by the
     * Producing application, so here we accept that multiple <code>null</code> values exist.
     *
     * @param p         The meta-data on the payload to check the reference for
     * @param paylaods  The meta-data on all submissionPayloadInfo included in the message
     * @return          <code>true</code> if the references are unique for each payload,<br>
     *                  <code>false</code> if duplicates exists
     */
    private boolean checkPayloadRefs(final IPayload p, final Collection<? extends IPayload> payloads) {
        boolean c = true;
        final Iterator<? extends IPayload> it = payloads.iterator();
        do {
            final IPayload p1 = it.next();
            final String   r0 = p.getPayloadURI(), r1 = p1.getPayloadURI();
            c = (p == p1)  // Same object, so always okay
                || p.getContainment() != p1.getContainment() // The containment differs
                    // The containment is attachment, so URI's should be different or both null
                || (p.getContainment() == IPayload.Containment.ATTACHMENT
                    && ((r0 == null && r1 == null) || !Utils.nullSafeEqualIgnoreCase (r0, r1)))
                    // The containment is body or external, URI should be different and not null
                || (r0 != null && r1 != null && !r0.equalsIgnoreCase(r1));
        } while (c && it.hasNext());
        return c;
    }

    /**
     * Helper method to copy or move the submissionPayloadInfo to an internal directory so they will be kept available
     * during the processing of the message (which may include resending).
     *
     * @param um     The meta data on the submitted user message
     * @param pmode  The P-Mode that governs the processing this user message
     * @throws IOException  When the payload could not be moved/copied to the internal payload storage
     * @throws PersistenceException When the new payload locations couldn't be saved to the database
     */
    private void moveOrCopyPayloads(final IUserMessageEntity um, final boolean move) throws IOException,
                                                                                            PersistenceException {
        // Path to the "temp" dir where to store submissionPayloadInfo during processing
        final String internalPayloadDir = HolodeckB2BCore.getConfiguration().getTempDirectory() + "plcout";
        // Create the directory if needed
        final Path pathPlDir = Paths.get(internalPayloadDir);
        if (!Files.exists(pathPlDir)) {
            log.debug("Create the directory [" + internalPayloadDir + "] for storing payload files");
            Files.createDirectories(pathPlDir);
        }

        final Collection<? extends IPayload> submissionPayloadInfo = um.getPayloads();
        Collection<IPayload> internalPayloadInfo = new ArrayList<>();
        if (!Utils.isNullOrEmpty(submissionPayloadInfo)) {
            for (final IPayload p : submissionPayloadInfo) {
                final Path srcPath = Paths.get(p.getContentLocation());
                // Ensure that the filename in the temp directory is unique
                final Path destPath = Utils.createFileWithUniqueName(internalPayloadDir + "/" + srcPath.getFileName());
                try {
                    if (move) {
                        log.trace("Moving payload [" + p.getContentLocation() + "] to internal directory");
                        Files.move(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        log.trace("Copying payload [" + p.getContentLocation() + "] to internal directory");
                        Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    // Complete payload info to store
                    Payload completeInfo = new Payload(p);
                    completeInfo.setContentLocation(destPath.toString());
                    internalPayloadInfo.add(completeInfo);
                } catch (IOException io) {
                    log.error("Could not copy/move the payload [" + p.getContentLocation() + "] to internal directory"
                             + " [" + internalPayloadDir + "].\n\tError details: " + io.getMessage());
                    // Remove the already created file for storing the payload
                    try {
                        Files.deleteIfExists(destPath);
                    } catch (IOException removeFailure) {
                        log.error("Could not remove the temporary payload file [" + destPath.toString() + "]!" +
                                  " Please remove manually.");
                    }
                    throw io;
                }
            }
            log.debug((move ? "Moved" : "Copied") + " all payloads to internal directory");
            HolodeckB2BCore.getStorageManager().setPayloadInformation(um, internalPayloadInfo);
        }
    }

    /**
     * Helper method that validates the submitted if custom validation has been specified in the P-Mode.
     *
     * @param submittedMsg  The meta-data of the submitted message
     * @param pmode         The P-Mode of the submitted message
     */
    private void validateMessage(IUserMessage submittedMsg, IPMode pmode) throws MessageSubmitException {
        // Get custom validation specifcation from P-Mode
        IMessageValidationSpecification validationSpec = null;
        try {
            validationSpec = HolodeckB2BCore.getPModeSet().get(submittedMsg.getPModeId())
                    .getLeg(ILeg.Label.REQUEST)
                    .getUserMessageFlow().getCustomValidationConfiguration();
        } catch (NullPointerException npe) {
            // Some element in the path to the validation spec is not available, so there is nothing to do
            log.error("The was a problem retrieving the validation specifcation from P-Mode ["
                    + submittedMsg.getPModeId() + "]!");
        }

        if (validationSpec == null)
            log.trace("No custom validation specified for submitted message");
        else {
            try {
                ValidationResult validationResult = HolodeckB2BCore.getValidationExecutor().validate(submittedMsg,
                                                                                                     validationSpec);
                if (validationResult == null || Utils.isNullOrEmpty(validationResult.getValidationErrors())) {
                    log.trace("Submitted message is valid");
                } else if (!validationResult.shouldRejectMessage()) {
                    log.warn("Submitted message contains validation errors, but can be processed");
                } else {
                    log.warn("Submitted message is not valid and must be rejected!");
                    throw new MessageSubmitException("Message rejected due to fatal validation errors!");
                }
            } catch (MessageValidationException ex) {
                log.error("Could not execute the validation of the submitted message!\n\tDetails: " + ex.getMessage());
                throw new MessageSubmitException("Could not execute the validation of the submitted message!", ex);
            }
        }
    }
}
