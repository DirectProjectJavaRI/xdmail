/* 
Copyright (c) 2010, NHIN Direct Project
All rights reserved.

Authors:
   Vincent Lewis     vincent.lewis@gsihealth.com
 
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
in the documentation and/or other materials provided with the distribution.  Neither the name of the The NHIN Direct Project (nhindirect.org). 
nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS 
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE 
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.nhind.mail.service;

import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import oasis.names.tc.ebxml_regrep.xsd.rs._3.RegistryResponseType;

import org.apache.commons.lang3.StringUtils;
import org.nhindirect.xd.proxy.DocumentRepositoryProxy;
import org.nhindirect.xd.soap.DirectSOAPHandlerResolver;
import org.nhindirect.xd.soap.SafeThreadData;

/**
 * Document repository class for handling XDS webservice calls.
 * 
 * @author Vince
 */
@Slf4j
public class DocumentRepository
{
    /**
     * Forward a given ProvideAndRegisterDocumentSetRequestType object to the
     * given XDR endpoint.
     * 
     * @param endpoint
     *            A URL representing an XDR endpoint.
     * @param prds
     *            The ProvideAndRegisterDocumentSetRequestType object.
     * @throws Exception
     */
    public String forwardRequest(String endpoint, ProvideAndRegisterDocumentSetRequestType prds, String directTo, String directFrom) throws Exception
    {
        if (StringUtils.isBlank(endpoint))
            throw new IllegalArgumentException("Endpoint must not be blank");
        if (prds == null)
            throw new IllegalArgumentException("ProvideAndRegisterDocumentSetRequestType must not be null");

        log.info(" SENDING TO ENDPOINT " + endpoint);

        /**
         * Get thread data by Thread ID
         */
        Long threadID = Thread.currentThread().getId();
        SafeThreadData threadData = SafeThreadData.GetThreadInstance(threadID);
        threadData.setAction("urn:ihe:iti:2007:ProvideAndRegisterDocumentSet-b");
        threadData.setMessageId(UUID.randomUUID().toString());
        threadData.setTo(endpoint);
        threadData.setDirectFrom(directFrom);
        threadData.setDirectTo(directTo);
        threadData.save();


        // Inspect the message
        //
        // QName qname = new QName("urn:ihe:iti:xds-b:2007", "ProvideAndRegisterDocumentSet_bRequest");
        // String body = XMLUtils.marshal(qname, prds, ihe.iti.xds_b._2007.ObjectFactory.class);
        // log.info(body);
        
        DocumentRepositoryProxy proxy = new DocumentRepositoryProxy(endpoint, new DirectSOAPHandlerResolver());

        try{
            RegistryResponseType rrt = proxy.provideAndRegisterDocumentSetB(prds);

            String response = rrt.getStatus();

            if (StringUtils.contains(response, "Failure"))
            {
                throw new Exception("Failure Returned from XDR forward");
            }
            SafeThreadData.clean(threadID);
            log.info("Handling complete");

            return response;
        } catch (Exception ex){
            SafeThreadData.clean(threadID);
            throw ex;
        }
    }

}

