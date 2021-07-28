/* 
 * Copyright (c) 2010, NHIN Direct Project
 * All rights reserved.
 *  
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright 
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright 
 *    notice, this list of conditions and the following disclaimer in the 
 *    documentation and/or other materials provided with the distribution.  
 * 3. Neither the name of the the NHIN Direct Project (nhindirect.org)
 *    nor the names of its contributors may be used to endorse or promote products 
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY 
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY 
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND 
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS 
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.nhind.james.matcher;

import java.util.ArrayList;
import java.util.Collection;

import javax.mail.MessagingException;

import org.apache.james.core.MailAddress;
import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMatcher;
import org.nhind.config.rest.AddressService;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.routing.impl.RoutingResolverImpl;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Matcher for non-XD mapped recipients.
 * 
 * @author beau
 */
@Slf4j
public class RecipientIsNotXd extends GenericMatcher
{	
    private RoutingResolver routingResolver;
    protected ApplicationContext ctx;
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void init()
    {
        log.info("Initializing RecipientIsNotXd matcher");

        ctx = new ClassPathXmlApplicationContext("contexts/STAMailet.xml");
        
        routingResolver = new RoutingResolverImpl(ctx.getBean(AddressService.class));

        log.info("Initialized RecipientIsNotXd matcher");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<MailAddress> match(Mail mail) throws MessagingException
    {
        log.info("Attempting to match non-XD recipients");

        Collection<MailAddress> recipients = new ArrayList<MailAddress>();

        for (MailAddress addr : (Collection<MailAddress>) mail.getRecipients())
        {
            if (!routingResolver.isXdEndpoint(addr.toString()))
            {
                recipients.add(addr);
            }
        }

        if (recipients.isEmpty())
            log.info("Matched no recipients");
        else
            for (MailAddress addr : recipients)
                log.info("Matched recipient " + addr.toString());

        return recipients;
    }
    
}
