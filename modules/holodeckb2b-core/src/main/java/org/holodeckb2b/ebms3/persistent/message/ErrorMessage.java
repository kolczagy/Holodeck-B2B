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

package org.holodeckb2b.ebms3.persistent.message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.persistence.CollectionTable;
import javax.persistence.DiscriminatorValue;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import org.holodeckb2b.common.messagemodel.IEbmsError;
import org.holodeckb2b.common.messagemodel.IErrorMessage;

/**
 * Is the persistency class representing the ebMS <i>Error Signal Message Unit</i>.
 * As described in the ebMS specification this message unit contains one or more
 * <i>errors</i> in addition to the standard ebMS header information. So this
 * class is quite simple and only contains a collection of {@see EbmsError} entity 
 * objects that represent the contained errors.
 * 
 * @author Sander Fieten <sander at holodeck-b2b.org>
 */
@Entity
@Table(name="ERROR_MESSAGE")
@DiscriminatorValue("ERRORMSG")
@NamedQueries({
        @NamedQuery(name="ErrorMessage.findForPModesInState",
            query = "SELECT err " +
                    "FROM ErrorMessage err JOIN err.states s1 " +
                    "WHERE err.PMODE_ID IN :pmodes " +
                    "AND s1.START = (SELECT MAX(s2.START) FROM err.states s2) " +
                    "AND s1.NAME = :state " +
                    "ORDER BY s1.START"  
            )}
)
public class ErrorMessage extends SignalMessage implements IErrorMessage {

    @Override
    public Collection<IEbmsError> getErrors() {
        return errors;
    }
    
    public void setErrors(Collection<EbmsError> errorList) {
        if (errors == null)
            errors = new ArrayList<IEbmsError>();
        errors.clear();
        errors.addAll(errorList);
    }
    
    public void addError(EbmsError e) {
        if (errors == null)
            errors = new ArrayList<IEbmsError>();
        errors.add(e);
    }
    
    /**
     * Returns a {@see String} representation of the error signal. This method can be
     * used for easily logging the error to text files, etc.
     * 
     * @return {@see String} representation of the error signal
     */
    @Override
    public String toString() {
        StringBuilder  errorMsg = new StringBuilder("ErrorSignal: msgId=");
        errorMsg.append(getMessageId()).append(", referenced message id=").append(getRefToMessageId()).append("\n");
        errorMsg.append("List of errors:");
        
        for(IEbmsError error : getErrors())
            errorMsg.append(error.toString()).append("\n");
        
        return errorMsg.toString();
    }
    
    /*
     * Fields
     * 
     * NOTES: 
     * 1) The JPA @Column annotation is not used so the attribute names are 
     * used as column names. Therefor the attribute names are in CAPITAL.
     * 2) The primary key field is inherited from super class
     */

    /**
     * Beside the standard ebMS meta-data the Error signal only contains a list
     * of errors
     */
    @ElementCollection(targetClass = EbmsError.class)
    @CollectionTable(name="ERR_MU_ERRORS")
    private List<IEbmsError>       errors;
}