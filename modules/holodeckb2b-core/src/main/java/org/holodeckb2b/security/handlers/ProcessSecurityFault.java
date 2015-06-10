/*
 * Copyright (C) 2015 The Holodeck B2B Team, Sander Fieten
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
package org.holodeckb2b.security.handlers;

import java.util.Collection;
import org.apache.axis2.context.MessageContext;
import org.holodeckb2b.common.exceptions.DatabaseException;
import org.holodeckb2b.common.handler.BaseHandler;
import org.holodeckb2b.ebms3.constants.SecurityConstants;
import org.holodeckb2b.ebms3.errors.FailedAuthentication;
import org.holodeckb2b.ebms3.errors.FailedDecryption;
import org.holodeckb2b.ebms3.errors.InvalidHeader;
import org.holodeckb2b.ebms3.persistent.dao.MessageUnitDAO;
import org.holodeckb2b.ebms3.persistent.message.MessageUnit;
import org.holodeckb2b.ebms3.util.MessageContextUtils;

/**
 * Is the <i>IN_FLOW</i> handler that checks for faults that occurred during processing of the WS-Security headers. If
 * processing of the WS-Security header fails on elements used for ebMS processing, i.e. encryption, signature and
 * username tokens, the message must not be processed. Therefor the processing state of all message units contained in 
 * the message must be set to <i>FAILED</i> and ebMS Errors must be generated.
 * 
 * 
 * @author Sander Fieten <sander at holodeck-b2b.org>
 */
public class ProcessSecurityFault extends BaseHandler {

    @Override
    protected byte inFlows() {
        return IN_FLOW | IN_FAULT_FLOW;
    }

    @Override
    protected InvocationResponse doProcessing(MessageContext mc) throws Exception {
        log.debug("Check for problems with processing WS-Security header(s)");
        
        // First check problems with the default header
        SecurityConstants.WSS_FAILURES failure = (SecurityConstants.WSS_FAILURES) 
                                                        mc.getProperty(SecurityConstants.INVALID_DEFAULT_HEADER);        
        if (failure != null) {
            log.debug("The default WS-Security header is invalid. Cause:" + failure);
            switch (failure) {
                case DECRYPTION : 
                    handleDecryptionFailure(mc);
                    break;                
                case SIGNATURE :
                case UT : 
                    handleAuthenticationFailure(mc);
                    break;                                    
                case UNKNOWN : 
                    handleOtherFailure(mc);
            }                             
        } else {
            log.debug("There was no default WS-Security header or it was succesfully validated");            
            // Check the header targeted to ebms role
            failure = (SecurityConstants.WSS_FAILURES) mc.getProperty(SecurityConstants.INVALID_EBMS_HEADER);
            // The only error that should occur is for the username token as there should be no other element in this
            // header.
            if (failure != null && failure == SecurityConstants.WSS_FAILURES.UT) {
                handleAuthenticationFailure(mc);
            } else if (failure != null)  {
                // The was another element in this header that caused an issue! As such an element is not allowed anyway
                // we generate an InvalidHeader error
                log.warn("Message contained additional element in WS-Security \"ebms\" header");
                handleInvalidHeader(mc);
            } else
                log.debug("There was no \"ebms\" WS-Security header or it was succesfully validated");            
        }
        
        log.debug("Check of WS-Security headers completed");        
        return InvocationResponse.CONTINUE;
    }
    
    /**
     * Handles a WS-Security failure cause by an error in decryption of the message.
     * <p>Because the message header is not encrypted, the information on the message units is read so we can generate
     * specific errors and update their processing state.
     * 
     * @param mc    The current message context
     */
    private void handleDecryptionFailure(MessageContext mc) throws DatabaseException {
        Collection<MessageUnit> rcvdMsgUnits = MessageContextUtils.getRcvdMessageUnits(mc); 
        if (rcvdMsgUnits != null && !rcvdMsgUnits.isEmpty()) {
            for (MessageUnit mu : rcvdMsgUnits) {        
                log.debug("Decryption of message containing the message unit [msgId=" 
                            + mu.getMessageId() + "] failed!");                
                FailedDecryption authError = new FailedDecryption();
                authError.setRefToMessageInError(mu.getMessageId());
                authError.setErrorDetail("Decryption of the message [unit] failed!");
                MessageContextUtils.addGeneratedError(mc, authError);
                // Set the processing state of the message unit to FAILED
                MessageUnitDAO.setFailed(mu); 
            }        
        } else {
            // No message units read from message, generate generic error
            log.debug("Decryption of message failed! No message units found in message.");                
            FailedDecryption authError = new FailedDecryption();
            authError.setErrorDetail("Decryption of the message failed!");
            MessageContextUtils.addGeneratedError(mc, authError);            
        }
    }

    /**
     * Handles a WS-Security failure cause by an error in the signature or the username token. As described in the ebMS
     * specification both errors result in an authentication failure.
     * 
     * @param mc    The current message context
     */
    private void handleAuthenticationFailure(MessageContext mc) throws DatabaseException {
        Collection<MessageUnit> rcvdMsgUnits = MessageContextUtils.getRcvdMessageUnits(mc);        
        for (MessageUnit mu : rcvdMsgUnits) {        
            log.debug("Authentication of message unit [msgId=" + mu.getMessageId() + "] failed!");                
            FailedAuthentication authError = new FailedAuthentication();
            authError.setRefToMessageInError(mu.getMessageId());
            authError.setErrorDetail("Authentication of message unit failed!");
            MessageContextUtils.addGeneratedError(mc, authError);
            // Set the processing state of the message unit to FAILED
            MessageUnitDAO.setFailed(mu); 
        }
    }

    /**
     * Handles a WS-Security failure cause by an error in the signature or the username token. As described in the ebMS
     * specification both errors result in an authentication failure.
     * 
     * @param mc    The current message context
     */
    private void handleInvalidHeader(MessageContext mc) throws DatabaseException {
        InvalidHeader invalidHdrErr = new InvalidHeader();
        invalidHdrErr.setErrorDetail("Message contains non allowed element in WS-Security header!");
        MessageContextUtils.addGeneratedError(mc, invalidHdrErr);

        // Set the processing state of all message units in message to FAILED
        Collection<MessageUnit> rcvdMsgUnits = MessageContextUtils.getRcvdMessageUnits(mc);        
        for (MessageUnit mu : rcvdMsgUnits) {        
            MessageUnitDAO.setFailed(mu); 
        }
    }
    
    /**
     * Handles a WS-Security failure cause by an error in an element not used for processing the ebMS message, e.g.
     * the Timestamp element. 
     * <p>Because the specifications do not state how to handle this we currently ignore these errors.
     * 
     * @param mc    The current message context
     */
    private void handleOtherFailure(MessageContext mc) {
        MessageUnit pmu = MessageContextUtils.getPrimaryMessageUnit(mc);
        log.warn("A problem with processing an element in the WS-Security header was ignored!" 
                 + "MsgId of primary message unit= " + (pmu != null ? pmu.getMessageId() : "not available"));        
    }
}