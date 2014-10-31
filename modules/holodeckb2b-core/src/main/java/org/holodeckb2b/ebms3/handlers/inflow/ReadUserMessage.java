/*
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

package org.holodeckb2b.ebms3.handlers.inflow;

import java.util.Iterator;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPHeaderBlock;
import org.apache.axis2.context.MessageContext;
import org.holodeckb2b.common.exceptions.DatabaseException;
import org.holodeckb2b.common.handler.BaseHandler;
import org.holodeckb2b.ebms3.constants.MessageContextProperties;
import org.holodeckb2b.ebms3.constants.ProcessingStates;
import org.holodeckb2b.ebms3.errors.InvalidHeader;
import org.holodeckb2b.ebms3.errors.OtherContentError;
import org.holodeckb2b.ebms3.packaging.Messaging;
import org.holodeckb2b.ebms3.packaging.PackagingException;
import org.holodeckb2b.ebms3.packaging.UserMessage;
import org.holodeckb2b.ebms3.persistent.dao.MessageUnitDAO;
import org.holodeckb2b.ebms3.util.MessageContextUtils;

/**
 * Is the handler in the <i>IN_FLOW</i> responsible for reading the meta data on an user message message unit from the 
 * received message (if such a message unit is included in the message). This data is contained in the 
 * <code>eb:UserMessage</code> element in the ebMS header.
 * <p>The meta data is stored in an {@link UserMessage} entity object which is stored in the database and added to the 
 * message context under key {@link MessageContextProperties#IN_USER_MESSAGE}. The processing state of the user message
 * is set to {@link ProcessingStates#PROCESSING}.
 * <p><b>NOTE:</b> The XML schema definition from the ebMS specification allows for multiple <code>eb:UserMessage</code> 
 * elements in the ebMS header. In the Core Specification however the number of user message units in the message
 * is limited to just one. Holodeck B2B therefor only uses the first occurrence of <code>eb:UserMessage</code> and 
 * ignores others.
 * 
 * @author Sander Fieten <sander at holodeck-b2b.org>
 */
public class ReadUserMessage extends BaseHandler {

    @Override
    protected byte inFlows() {
        return IN_FLOW;
    }

    @Override
    protected InvocationResponse doProcessing(MessageContext mc) {
        // First get the ebMS header block, that is the eb:Messaging element
        SOAPHeaderBlock messaging = Messaging.getElement(mc.getEnvelope());
        
        if (messaging != null) {
            // Check if there is a user message unit
            log.debug("Check for UserMessage element");
            Iterator it = UserMessage.getElements(messaging);
            if (it.hasNext()) {
                log.debug("UserMessage found, read information from message");
                OMElement umElement = (OMElement) it.next();
                org.holodeckb2b.ebms3.persistent.message.UserMessage umData = null;
                try {
                    // Read information into UserMessage entity object
                    umData = UserMessage.readElement(umElement);
                    log.debug("Succesfully read user message meta data from header");                
                } catch (PackagingException ex) {
                    // The UserMessage element in the message does not comply with the spec,
                    //  so it can not be further processed. 
                    log.warn("Received message contains an invalid ebMS user message!");
                    
                    // Add an error to context, maybe it can be sent as response
                    InvalidHeader   invalidHdrError = new InvalidHeader();
                    invalidHdrError.setErrorDetail("Source of header containing the error:" + umElement.toString());
                    MessageContextUtils.addGeneratedError(mc, invalidHdrError);
                    
                    return InvocationResponse.CONTINUE;
                }
                
                // Store it in both database and message context for further processing
                try {
                    log.debug("Saving user message meta data to database");
                    MessageUnitDAO.storeReceivedMessageUnit(umData);
                    // We immediately start processing the message, so change state in database
                    umData = MessageUnitDAO.startProcessingMessageUnit(umData);
                    log.debug("Message meta data saved to database");
                    
                    mc.setProperty(MessageContextProperties.IN_USER_MESSAGE, umData);
                    log.debug("User message with msgId " + umData.getMessageId() + " succesfully read");  
                } catch (DatabaseException ex) {
                    // Oops, something went wrong saving the data
                    log.error("A error occurred when saving user message to database. Details: " + ex.getMessage());
                    // Maybe this is just a glitch and the error can still be processed
                    OtherContentError  dbFailure = new OtherContentError();
                    dbFailure.setErrorDetail("Internal error prevented processing of message!");
                    dbFailure.setCategory("Internal");
                    
                    MessageContextUtils.addGeneratedError(mc, dbFailure);
                }
            }
        }

        return InvocationResponse.CONTINUE;
    }
    
}