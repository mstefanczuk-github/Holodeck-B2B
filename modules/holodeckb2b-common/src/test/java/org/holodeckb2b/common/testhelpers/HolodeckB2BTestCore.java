/*
 * Copyright (C) 2016 The Holodeck B2B Team, Sander Fieten
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
package org.holodeckb2b.common.testhelpers;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.holodeckb2b.common.util.Utils;
import org.holodeckb2b.interfaces.config.IConfiguration;
import org.holodeckb2b.interfaces.core.IHolodeckB2BCore;
import org.holodeckb2b.interfaces.delivery.IDeliverySpecification;
import org.holodeckb2b.interfaces.delivery.IMessageDeliverer;
import org.holodeckb2b.interfaces.delivery.IMessageDelivererFactory;
import org.holodeckb2b.interfaces.delivery.MessageDeliveryException;
import org.holodeckb2b.interfaces.eventprocessing.IMessageProcessingEventConfiguration;
import org.holodeckb2b.interfaces.eventprocessing.IMessageProcessingEventProcessor;
import org.holodeckb2b.interfaces.eventprocessing.MessageProccesingEventHandlingException;
import org.holodeckb2b.interfaces.persistency.dao.IQueryManager;
import org.holodeckb2b.interfaces.pmode.IPModeSet;
import org.holodeckb2b.interfaces.security.ICertificateManager;
import org.holodeckb2b.interfaces.security.SecurityProcessingException;
import org.holodeckb2b.interfaces.submit.IMessageSubmitter;
import org.holodeckb2b.interfaces.workerpool.IWorkerPoolConfiguration;
import org.holodeckb2b.interfaces.workerpool.TaskConfigurationException;

/**
 * Is utility class for testing that simulates the Holodeck B2B Core.
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
public class HolodeckB2BTestCore implements IHolodeckB2BCore {

	private IConfiguration		configuration;
	private IMessageSubmitter	messageSubmitter;
	private IPModeSet			pmodes;
	private IMessageProcessingEventProcessor eventProcessor;
	private ICertificateManager certManager;
	private IQueryManager		queryManager;
	private List<IMessageProcessingEventConfiguration> eventConfig = new ArrayList<>();
	private Map<String, IMessageDelivererFactory> msgDeliveryMethods = new HashMap<>();
	
	public HolodeckB2BTestCore() {
	}

	public HolodeckB2BTestCore(final String homeDir) {
		this.configuration = new TestConfig(homeDir);
	}
	
	public HolodeckB2BTestCore(final IConfiguration config) {
		this.configuration = config;
	}	
	
	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getConfiguration()
	 */
	@Override
	public IConfiguration getConfiguration() {
		return configuration;
	}
	
	public void cleanTemp() {
		String tmpDir = configuration != null ? configuration.getTempDirectory() : null;
		if (tmpDir != null) 
			deleteDirectory(new File(tmpDir));
	}

	private static boolean deleteDirectory(File directory) {
	    if(directory.exists()){
	        File[] files = directory.listFiles();
	        if(null!=files){
	            for(int i=0; i<files.length; i++) {
	                if(files[i].isDirectory()) {
	                    deleteDirectory(files[i]);
	                }
	                else {
	                    files[i].delete();
	                }
	            }
	        }
	    }
	    return(directory.delete());
	}	
	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getMessageDeliverer(org.holodeckb2b.interfaces.delivery.IDeliverySpecification)
	 */
	@Override
	public IMessageDeliverer getMessageDeliverer(IDeliverySpecification deliverySpec) throws MessageDeliveryException {
		IMessageDelivererFactory mdf = msgDeliveryMethods.get(deliverySpec.getId());
		if (mdf == null) {
	        try {
	            final String factoryClassName = deliverySpec.getFactory();
	            mdf = (IMessageDelivererFactory) Class.forName(factoryClassName).newInstance();
	        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | ClassCastException ex) {
	            // Somehow the factory class failed to load
	            throw new MessageDeliveryException("Factory class not available!", ex);
	        }
	        // Initialize the new factory with the settings from the delivery spec
	        mdf.init(deliverySpec.getSettings());
	        msgDeliveryMethods.put(deliverySpec.getId(), mdf);
		}
        return mdf.createMessageDeliverer();
	}
	
	public void setMessageSubmitter(final IMessageSubmitter submitter) {
		this.messageSubmitter = submitter;
	}

	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getMessageSubmitter()
	 */
	@Override
	public IMessageSubmitter getMessageSubmitter() {
		if (messageSubmitter == null)
			synchronized (this) {
				if (messageSubmitter == null)
					messageSubmitter = new Submitter();
			}
		return messageSubmitter;
	}

	public void setPModeSet(final IPModeSet pmodeSet) {
		this.pmodes = pmodeSet;
	}
	
	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getPModeSet()
	 */
	@Override
	public IPModeSet getPModeSet() {
		if (pmodes == null)
			pmodes = new SimplePModeSet();
		return pmodes;
	}

	public void setMessageProcessingEventProcessor(final IMessageProcessingEventProcessor processor) {
		this.eventProcessor = processor;
	}
	
	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getEventProcessor()
	 */
	@Override
	public IMessageProcessingEventProcessor getEventProcessor() {
		if (eventProcessor == null)
			eventProcessor = new TestEventProcessor();
		return eventProcessor;
	}

	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#setPullWorkerPoolConfiguration(org.holodeckb2b.interfaces.workerpool.IWorkerPoolConfiguration)
	 */
	@Override
	public void setPullWorkerPoolConfiguration(IWorkerPoolConfiguration pullConfiguration) throws TaskConfigurationException {
	}

	public void setQueryManager(final IQueryManager queryManager) {
		this.queryManager = queryManager;
	}
	
	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getQueryManager()
	 */
	@Override
	public IQueryManager getQueryManager() {
		return queryManager;
	}

	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getCertificateManager()
	 */
	@Override
	public ICertificateManager getCertificateManager() {
		if (certManager == null)
			try {
				certManager = new InMemoryCertificateManager();
			} catch (SecurityProcessingException e) {				
			}
		return certManager;
	}

	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#registerEventHandler(org.holodeckb2b.interfaces.eventprocessing.IMessageProcessingEventConfiguration)
	 */
	@Override
	public boolean registerEventHandler(IMessageProcessingEventConfiguration eventConfiguration)
			throws MessageProccesingEventHandlingException {
    	final String id = eventConfiguration.getId();
    	if (Utils.isNullOrEmpty(id))
    		throw new MessageProccesingEventHandlingException("No id specified");
    	
    	int i; boolean exists = false;
    	for(i = 0; i < eventConfig.size() && !exists; i++)
    		exists = eventConfig.get(i).getId().equals(id);
    	if (exists) 
    		eventConfig.set(i, eventConfiguration);
    	else 
    		eventConfig.add(eventConfiguration);
    	return exists;
	}

	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#removeEventHandler(java.lang.String)
	 */
	@Override
	public void removeEventHandler(String id) {
    	int i; boolean exists = false;
    	for(i = 0; i < eventConfig.size() && !exists; i++)
    		exists = eventConfig.get(i).getId().equals(id);
    	if (exists)     		
    		eventConfig.remove(i);
	}

	/* (non-Javadoc)
	 * @see org.holodeckb2b.interfaces.core.IHolodeckB2BCore#getEventHandlerConfiguration()
	 */
	@Override
	public List<IMessageProcessingEventConfiguration> getEventHandlerConfiguration() {
		return eventConfig;
	}
}
