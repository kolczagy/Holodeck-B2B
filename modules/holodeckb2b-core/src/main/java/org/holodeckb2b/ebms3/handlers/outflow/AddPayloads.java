/*
 * Copyright (C) 2013 The Holodeck B2B Team, Sander Fieten
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
package org.holodeckb2b.ebms3.handlers.outflow;

import java.io.File;
import java.io.FileReader;
import java.util.Collection;
import javax.activation.FileDataSource;
import javax.xml.namespace.QName;
import org.apache.axiom.attachments.ConfigurableDataHandler;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.OMNamespace;
import org.apache.axiom.om.OMXMLBuilderFactory;
import org.apache.axiom.om.OMXMLParserWrapper;
import org.apache.axis2.context.MessageContext;
import org.holodeckb2b.common.exceptions.DatabaseException;
import org.holodeckb2b.common.general.Constants;
import org.holodeckb2b.common.messagemodel.IPayload;
import org.holodeckb2b.common.util.MessageIdGenerator;
import org.holodeckb2b.common.util.Utils;
import org.holodeckb2b.ebms3.persistent.dao.MessageUnitDAO;
import org.holodeckb2b.ebms3.persistent.message.Payload;
import org.holodeckb2b.ebms3.persistent.message.UserMessage;
import org.holodeckb2b.ebms3.util.AbstractUserMessageHandler;

/**
 * When there is a <i>User Message unit</i> to be sent, this handler will add the payloads to the SOAP message. 
 * <p>This is only the first step of a two step process as payload meta data must also be added to the ebMS SOAP header.
 * Adding the payload meta data to ebMS header is done by the {@link PackageUsermessageInfo} handler. This is done later
 * in the pipeline as meta data should only be added if the payload content can be successfully included in the
 * message.
 * <br>The content of the payloads are normally added as SOAP Attachments. But it is possible to include one or more XML 
 * documents in the SOAP body. Alternatively the content can be external to message, for example stored on a web site, 
 * in which case this handler does nothing.
 * 
 * @author Sander Fieten <sander at holodeck-b2b.org>
 */
public class AddPayloads extends AbstractUserMessageHandler {

    /**
     * This handler should run only in a normal <i>OUT_FLOW</i> 
     */
    @Override
    protected byte inFlows() {
        return OUT_FLOW;
    }
    
    @Override
    protected InvocationResponse doProcessing(MessageContext mc, UserMessage um) {
        
        log.debug("Check for payloads to include");
        Collection<IPayload> payloads = um.getPayloads();
        
        if (payloads == null || payloads.isEmpty()) {
            // No payloads in this user message, so nothing to do
            log.debug("Usser message has no payloads");
        } else {
            // Add each payload to the message as described by the containment attribute
            log.debug("User message contains " + payloads.size() + " payload(s)");
            
            for (IPayload pl : payloads) {
                // First check that the payload can be correctly referenced. This is
                // done first because for attached payloads this reference is the
                // MIME Content-id which is needed when adding the payload
                if (!checkAndSetReference((Payload) pl, um)) {
                    log.error("Inconsistent payload meta data for message " + 
                                um.getMessageId() + "! Unable to build correct ebMS message");
                    // Because message state is unknown abort send process
                    return InvocationResponse.ABORT;
                }
                
                // Second add the content of the payload to the SOAP message
                try {
                    addContent(pl, mc);
                } catch (Exception e) {
                    log.error("Adding the payload content to message failed. Error details: " + e.getMessage());
                    // If adding the payload fails, the message is in an incomplete state and should not
                    // be sent. Therefor cancel further processing
                    return InvocationResponse.ABORT;
                }
            }

            try {
                MessageUnitDAO.updatePayloadMetaData(um);
            } catch (DatabaseException ex) {
                log.error("An error occurred while updating the payload information in the database!" 
                            + "Details: " + ex.getMessage());
            }
            log.debug("Payloads successfully added to message");                
        }
        
        return InvocationResponse.CONTINUE;
    }

    
    /**
     * Checks whether the given {@link Payload} can be correctly referenced in the
     * context of the {@link UserMessage}. The reference to the payload is given by
     * {@link Payload#getPayloadURI()} and must be unique for all payloads in this
     * user message with the same containment type, as given by {@link IPayload#getContainment()}.
     * <p>If the submitter of the user message has not specified a reference 
     * Holodeck B2B will generate one if the payload should be attached to the 
     * message, i.e. when {@link IPayload#getContainment()} returns {{@link IPayload.Containment#ATTACHMENT}
     * 
     * @param p     The payload to check the reference of
     * @param um    The user message the payload is part of
     * @return      <code>true</code> when the reference is correct (unique),
     *              <code>false</code> if there exists another simular included 
     *              payload with the same reference
     */
    protected boolean checkAndSetReference(Payload p, UserMessage um) {
        String r = p.getPayloadURI();
        
        // If current reference is empty and the payload should be attached to
        // the message, generate a Content-id
        if ((p.getContainment() == IPayload.Containment.ATTACHMENT) && (r == null || r.isEmpty())) {
            log.debug("Generating a new Content-id for payload");
            // At this point the Payload object is not managed and the set reference
            // will not be directly saved in the database. This will be done when all payloads
            // are added 
            p.setPayloadURI(MessageIdGenerator.createContentId(um.getMessageId()));
            log.debug("Using " + p.getPayloadURI() + " as Content-id");
            
            return true;
        } else {
            // Check correctness of reference
            boolean c = true;
            for (IPayload pl : um.getPayloads()) {
                // The reference must be unique within the set of simularly included payloads,
                // so there must be no other payload with the same containment and reference
                // Whether the Payload refers to another actual payload is determined by
                // the content location.
                c = p.getContainment() != pl.getContainment() || 
                    p.getContentLocation().equals(pl.getContentLocation()) ||
                    !r.equals(pl.getPayloadURI());
                if (!c) break; // When not correct stop processing
            }
            
            if (c) 
                log.debug("Using " + p.getPayloadURI() + " as Content-id");
            else
                log.warn(p.getPayloadURI() + " is not a unique payload reference in message " + um.getMessageId());
            
            return c;
        }
    }
    
    /**
     * Adds the payload content to the SOAP message. How the content is added to the message depends on the 
     * <i>containment</i> of the payload ({@link IPayload#getContainment()}).
     * <p>The default containment is {@link IPayload.Containment#ATTACHMENT} in which case the payload content as added 
     * as a SOAP attachment. The payload will be referred to from the ebMS header by the MIME Content-id of the MIME 
     * part. This Content-id MUST specified in the message meta-data ({@link IPayload#getPayloadURI()}).
     * <p>When the containment is specified as {@link IPayload.Containment#EXTERNAL} no content will
     * be added to SOAP message. It is assumed that transfer of the content takes place out
     * of band.
     * <p>When {@link IPayload.Containment#BODY} is specified as containment the content should be added to the SOAP Body. 
     * This requires the payload content to be a XML document. If the specified content is not, an exception is thrown.<br>
     * <b>NOTE:</b> A payload included in the body is referred to from the ebMS header by the <code>id</code> attribute 
     * of the root element of the XML Document. The submitted message meta data however can also included a reference 
     * ({@see IPayload#getPayloadURI()}). In case both the payload and the message meta data included an id the 
     * submitter MUST ensure that the value is the same. If not the payload will not be included in the message and this 
     * method will throw an exception.
     * 
     * @param p             The payload that should be added to the message
     * @param mc            The Axis2 message context for the outgoing message
     * @throws Exception    When a problem occurs while adding the payload contents to
     *                      the message
     */
    protected void addContent(IPayload p, MessageContext mc) throws Exception {
        File f = null;
                
        switch (p.getContainment()) {
            case ATTACHMENT : 
                log.debug("Adding payload as attachment. Content located at " + p.getContentLocation());
                f = new File(p.getContentLocation());
                
                // Use specified MIME type or detect it when none is specified
                String mimeType = p.getMimeType();
                if (mimeType == null || mimeType.isEmpty()) {
                    log.debug("Detecting MIME type of payload");
                    mimeType = Utils.detectMimeType(f);
                }
                        
                log.debug("Payload mime type is " + mimeType);
                // Use Axiom ConfigurableDataHandler to enable setting of mime type
                ConfigurableDataHandler dh = new ConfigurableDataHandler(new FileDataSource(f));
                dh.setContentType(mimeType);
                
                // Check if a Content-id is specified, if not generate one now
                String cid = p.getPayloadURI();
                if (cid == null || cid.isEmpty()) {
                    log.warn("Content-id missing for payload.");
                    throw new Exception("Content-id missing for payload");
                } 
                
                log.debug("Add payload to message as attachment with Content-id: " + cid);
                mc.addAttachment(cid, dh);
                log.info("Payload content located at '" + p.getContentLocation() + "' added to message");
                
                return;
            case BODY : 
                log.debug("Adding payload to SOAP body. Content located at " + p.getContentLocation());
                f = new File(p.getContentLocation());
                
                try {
                    log.debug("Parse the XML from file so it can be added to SOAP body");
                    OMXMLParserWrapper builder = OMXMLBuilderFactory.createOMBuilder(new FileReader(f));
                    OMElement documentElement = builder.getDocumentElement();
                    
                    // Check that refence and id are equal if both specified 
                    String href = p.getPayloadURI();
                    String xmlId = documentElement.getAttributeValue(new QName("id"));
                    if( href != null && xmlId != null && !href.equals(xmlId)) {
                        log.error("Payload reference [" + href + "] and id of payload element [" + xmlId + 
                                    "] are not equal! Can not create consistent message.");
                        throw new Exception("Payload reference [" + href + "] and id of payload element [" + xmlId + 
                                    "] are not equal! Can not create consistent message.");
                    } else if (href != null && (xmlId == null || xmlId.isEmpty())) {
                        log.debug("Set specified reference in meta data [" + href + "] as xml:id on root element");
                        OMNamespace xmlIdNS = documentElement.declareNamespace(Constants.QNAME_XMLID.getNamespaceURI(), "xml");
                        documentElement.addAttribute(Constants.QNAME_XMLID.getLocalPart(), href, xmlIdNS);                        
                    }
                    
                    log.debug("Add payload XML to SOAP Body");
                    mc.getEnvelope().getBody().addChild(documentElement);
                    log.info("Payload content located at '" + p.getContentLocation() + "' added to message");                
                } catch (OMException parseError) {
                    // The given document could not be parsed, probably not an XML document. Reject it as body payload 
                    log.error("Failed to parse payload located at " + p.getContentLocation() + "!");
                    throw new Exception("Failed to parse payload located at " + p.getContentLocation() + "!", 
                                        parseError);
                }
                return;
        }
    }


}