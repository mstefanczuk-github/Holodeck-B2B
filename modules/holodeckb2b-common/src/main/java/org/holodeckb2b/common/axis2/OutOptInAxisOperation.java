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
package org.holodeckb2b.common.axis2;

import javax.xml.namespace.QName;

import org.apache.axiom.soap.SOAPBody;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.client.OperationClient;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.async.AxisCallback;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.ClientUtils;
import org.apache.axis2.description.OutInAxisOperation;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.i18n.Messages;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.util.Utils;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.holodeckb2b.common.handler.MessageProcessingContext;


/**
 * Is a special Axis2 {@link AxisOperation} implementation that supports the Out In MEP but does not require a response
 * message and which allows to specify  
 * 
 * <p>This class extends {@link OutInAxisOperation} to return a different {@link OperationClient} implementation.
 * Although there is just one method that changes in the <code>OperationClient</code> it must be copied from the super
 * class a an inner class can not be extended.
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
public class OutOptInAxisOperation extends OutInAxisOperation {

    /**
     * Create a new instance of the OutOptInAxisOperation
     */
    public OutOptInAxisOperation(final QName name) {
        super(name);
        setMessageExchangePattern(WSDL2Constants.MEP_URI_OUT_IN);
    }

    /**
     * Returns the MEP client for an Out-IN operation that accepts an empty response. To use the client, you must call
     * addMessageContext() with a message context and then call execute() to execute the client.
     *
     * @param sc      The service context for this client to live within. Cannot be
     *                null.
     * @param options Options to use as defaults for this client. If any options are
     *                set specifically on the client then those override options
     *                here.
     */
    @Override
    public OperationClient createClient(final ServiceContext sc, final Options options) {
        return new OutOptInAxisOperationClient(this, sc, options);
    }

    /**
     * The client to handle the MEP. This is a copy of <code>OutInAxisOperationClient<code> inner class of {@link
     * OutInAxisOperation} with an adjusted {@link #handleResponse(MessageContext)} method.
     */
    protected class OutOptInAxisOperationClient extends OperationClient {

        private final Log log = LogFactory.getLog(OutOptInAxisOperationClient.class);

        public OutOptInAxisOperationClient(final OutInAxisOperation axisOp, final ServiceContext sc, final Options options) {
            super(axisOp, sc, options);
        }

        /**
         * Adds message context to operation context, so that it will handle the logic correctly if the OperationContext
         * is null then new one will be created, and Operation Context will become null when some one calls reset().
         *
         * @param msgContext the MessageContext to add
         * @throws AxisFault
         */
        @Override
        public void addMessageContext(final MessageContext msgContext) throws AxisFault {
            msgContext.setServiceContext(sc);
            if (msgContext.getMessageID() == null) {
                setMessageID(msgContext);
            }
            axisOp.registerOperationContext(msgContext, oc);
        }

        /**
         * Returns the message context for a given message label.
         *
         * @param messageLabel : label of the message and that can be either "Out" or "In" and nothing else
         * @return Returns MessageContext.
         * @throws AxisFault
         */
        @Override
        public MessageContext getMessageContext(final String messageLabel)
                throws AxisFault {
            return oc.getMessageContext(messageLabel);
        }

        /**
         * Executes the MEP. What this does depends on the specific MEP client. The basic idea is to have the MEP client
         * execute and do something with the messages that have been added to it so far. For example, if its an Out-In
         * MEP, then if the Out message has been set, then executing the client asks it to send the message and get the
         * In message, possibly using a different thread.
         *
         * @param block Indicates whether execution should block or return ASAP. What block means is of course a
         * function of the specific MEP client. IGNORED BY THIS MEP CLIENT.
         * @throws AxisFault if something goes wrong during the execution of the MEP.
         */
        @Override
        public void executeImpl(final boolean block) throws AxisFault {
            if (completed) {
                throw new AxisFault(Messages.getMessage("mepiscomplted"));
            }
            final ConfigurationContext cc = sc.getConfigurationContext();

            // copy interesting info from options to message context.
            final MessageContext mc = oc.getMessageContext(WSDLConstants.MESSAGE_LABEL_OUT_VALUE);
            if (mc == null) {
                throw new AxisFault(Messages.getMessage("outmsgctxnull"));
            }
            prepareMessageContext(cc, mc);

            if (options.getTransportIn() == null && mc.getTransportIn() == null) {
                mc.setTransportIn(ClientUtils.inferInTransport(cc
                        .getAxisConfiguration(), options, mc));
            } else if (mc.getTransportIn() == null) {
                mc.setTransportIn(options.getTransportIn());
            }

            if (block) {
                // Send the SOAP Message and receive a response
                send(mc);
                completed = true;
            } else {
            sc.getConfigurationContext().getThreadPool().execute(
                    new OutOptInAxisOperationClient.NonBlockingInvocationWorker(mc, axisCallback));
            }
        }

        /**
         * When synchronous send() gets back a response MessageContext, this is the workhorse method which processes it.
         *
         * @param responseMessageContext the active response MessageContext
         * @throws AxisFault if something went wrong
         */
        protected void handleResponse(final MessageContext responseMessageContext) throws AxisFault {
        // Options object reused above so soapAction needs to be removed so
            // that soapAction+wsa:Action on response don't conflict
            responseMessageContext.setSoapAction(null);

            if (responseMessageContext.getEnvelope() == null) {
                try {
                    final SOAPEnvelope resenvelope = TransportUtils.createSOAPMessage(responseMessageContext);
                    if (resenvelope != null)
                        responseMessageContext.setEnvelope(resenvelope);
                } catch (final AxisFault af) {
                    // This AxisFault indicates that there was no response received. Because this is allowd in ebMS
                    // exchanges we just ignore this.
                }

            }
            SOAPEnvelope resenvelope = responseMessageContext.getEnvelope();
            if (resenvelope != null) {
                AxisEngine.receive(responseMessageContext);
                if (responseMessageContext.getReplyTo() != null) {
                    sc.setTargetEPR(responseMessageContext.getReplyTo());
                }
            }
        }

        /**
         * Synchronously send the request and receive a response. This relies on the transport correctly connecting the
         * response InputStream!
         *
         * @param msgContext the request MessageContext to send.
         * @return Returns MessageContext.
         * @throws AxisFault Sends the message using a two way transport and waits for a response
         */
        protected MessageContext send(final MessageContext msgContext) throws AxisFault {

        // create the responseMessageContext
            final MessageContext responseMessageContext
                    = msgContext.getConfigurationContext().createMessageContext();

            responseMessageContext.setServerSide(false);
            responseMessageContext.setOperationContext(msgContext.getOperationContext());
            responseMessageContext.setOptions(new Options(options));
            responseMessageContext.setMessageID(msgContext.getMessageID());
            addMessageContext(responseMessageContext);
            responseMessageContext.setServiceContext(msgContext.getServiceContext());
            responseMessageContext.setAxisMessage(
                    axisOp.getMessage(WSDLConstants.MESSAGE_LABEL_IN_VALUE));

            //sending the message
            AxisEngine.send(msgContext);

            responseMessageContext.setDoingREST(msgContext.isDoingREST());
            
            // Copy RESPONSE properties which the transport set onto the request message context when it processed
            // the incoming response recieved in reply to an outgoing request.
            MessageProcessingContext hb2bMsgProcCtx = MessageProcessingContext.getFromMessageContext(msgContext);
            hb2bMsgProcCtx.setParentContext(responseMessageContext);
            
            responseMessageContext.setProperty(MessageContext.TRANSPORT_HEADERS,
                    msgContext.getProperty(MessageContext.TRANSPORT_HEADERS));
            responseMessageContext.setProperty(HTTPConstants.MC_HTTP_STATUS_CODE,
                    msgContext.getProperty(HTTPConstants.MC_HTTP_STATUS_CODE));

            responseMessageContext.setProperty(MessageContext.TRANSPORT_IN, msgContext
                    .getProperty(MessageContext.TRANSPORT_IN));
            responseMessageContext.setTransportIn(msgContext.getTransportIn());
            responseMessageContext.setTransportOut(msgContext.getTransportOut());
            handleResponse(responseMessageContext);
            return responseMessageContext;
        }

    /**
     * This class is the workhorse for a non-blocking invocation that uses a two
     * way transport.
     */
    private class NonBlockingInvocationWorker implements Runnable {
        private MessageContext msgctx;
        private AxisCallback axisCallback;

        public NonBlockingInvocationWorker(MessageContext msgctx ,
                                           AxisCallback axisCallback) {
            this.msgctx = msgctx;
            this.axisCallback =axisCallback;
        }

        @Override
		public void run() {
            try {
                // send the request and wait for response
                MessageContext response = send(msgctx);
                // call the callback
                if (response != null) {
                    SOAPEnvelope resenvelope = response.getEnvelope();

                    if (resenvelope.hasFault()) {
                        SOAPBody body = resenvelope.getBody();
                        // If a fault was found, create an AxisFault with a MessageContext so that
                        // other programming models can deserialize the fault to an alternative form.
                        AxisFault fault = new AxisFault(body.getFault(), response);
                        if (axisCallback != null) {
                            if (options.isExceptionToBeThrownOnSOAPFault()) {
                                axisCallback.onError(fault);
                            } else {
                                axisCallback.onFault(response);
                            }
                        }

                    } else {
                        if (axisCallback != null) {
                            axisCallback.onMessage(response);
                        }

                    }
                }

            } catch (Exception e) {
                if (axisCallback != null) {
                    axisCallback.onError(e);
                }

            } finally {
                if (axisCallback != null) {
                    axisCallback.onComplete();
                }
            }
        }
    }

    /**
     * This class acts as a callback that allows users to wait on the result.
     */
    private class SyncCallBack implements AxisCallback {
        boolean complete;
        boolean receivedFault;

        public boolean waitForCompletion(long timeout) throws AxisFault {
            synchronized (this) {
                try {
                    if (complete) return !receivedFault;
                    wait(timeout);
                    if (!complete) {
                        // We timed out!
                        throw new AxisFault( Messages.getMessage("responseTimeOut"));
                    }
                } catch (InterruptedException e) {
                    // Something interrupted our wait!
                    error = e;
                }
            }

            if (error != null) throw AxisFault.makeFault(error);

            return !receivedFault;
        }

        /**
         * This is called when we receive a message.
         *
         * @param msgContext the (response) MessageContext
         */
        @Override
		public void onMessage(MessageContext msgContext) {
            // Transport input stream gets closed after calling setComplete
            // method. Have to build the whole envelope including the
            // attachments at this stage. Data might get lost if the input
            // stream gets closed before building the whole envelope.

            // TODO: Shouldn't need to do this - need to hook up stream closure to Axiom completion
            this.envelope = msgContext.getEnvelope();
            this.envelope.buildWithAttachments();
        }

        /**
         * This gets called when a fault message is received.
         *
         * @param msgContext the MessageContext containing the fault.
         */
        @Override
		public void onFault(MessageContext msgContext) {
           error = Utils.getInboundFaultFromMessageContext(msgContext);
        }

        /**
         * This is called at the end of the MEP no matter what happens, quite like a
         * finally block.
         */
        @Override
		public synchronized void onComplete() {
			complete = true;
            notify();
        }

        private SOAPEnvelope envelope;

        private Exception error;

        @Override
		public void onError(Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Entry: OutInAxisOperationClient$SyncCallBack::onError, " + e);
            }
            error = e;
            if (log.isDebugEnabled()) {
                log.debug("Exit: OutInAxisOperationClient$SyncCallBack::onError");
            }
        }
    }
    }
}

