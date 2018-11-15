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

package org.nhind.james.mailet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.mailet.Mail;
import org.nhind.config.rest.AddressService;
import org.nhind.mail.service.DocumentRepository;
import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhindirect.common.mail.MDNStandard;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.gateway.smtp.NotificationProducer;
import org.nhindirect.gateway.smtp.NotificationSettings;
import org.nhindirect.gateway.smtp.ReliableDispatchedNotificationProducer;
import org.nhindirect.gateway.smtp.dsn.DSNCreator;
import org.nhindirect.gateway.smtp.dsn.impl.FailedDeliveryDSNCreator;
import org.nhindirect.gateway.smtp.james.mailet.AbstractNotificationAwareMailet;
import org.nhindirect.gateway.util.MessageUtils;
import org.nhindirect.stagent.NHINDAddress;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.routing.impl.RoutingResolverImpl;
import org.nhindirect.xd.transform.MimeXdsTransformer;
import org.nhindirect.xd.transform.impl.DefaultMimeXdsTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * An Apache James Mailet that converts clinical messages into IHE
 * Cross-Enterprise Document Reliability (XDR) messages and transmits them to an
 * XDR Document Recipient via IHE XDS.b Provide and Register transaction
 * (ITI-41).
 *
 */
public class DirectXdMailet extends AbstractNotificationAwareMailet implements XDDeliveryCallback
{
	protected static final String RELIABLE_DELIVERY_OPTION = MDNStandard.DispositionOption_TimelyAndReliable + "=optional,true";

    private String endpointUrl;

    private MimeXdsTransformer mimeXDSTransformer;
    private DocumentRepository documentRepository;
    private RoutingResolver resolver;
    private XDDeliveryCore deliveryCore;
    protected NotificationProducer notificationProducer;
	private static final Logger LOGGER = LoggerFactory.getLogger(DirectXdMailet.class);	

    
    /*
     * (non-Javadoc)
     * 
     * @see org.apache.mailet.base.GenericMailet#service(org.apache.mailet.Mail)
     */
    @Override
    public void service(Mail mail) throws MessagingException
    {
        LOGGER.info("Servicing DirectXdMailet");

        final SMTPMailMessage smtpMailMessage = mailToSMTPMailMessage(mail);
        
		final NHINDAddressCollection initialRecipients = MessageUtils.getMailRecipients(smtpMailMessage);
        
        deliveryCore.processAndDeliverXDMessage(smtpMailMessage);
		

		
        // Get recipients and create a collection of Strings
        final List<String> recipAddresses = new ArrayList<String>();

        for (NHINDAddress addr : initialRecipients)
            recipAddresses.add(addr.getAddress());


        // Service SMTP addresses (fall through)
        // this basically sets the message back to it's original state with SMTP addresses only
        if (getResolver().hasSmtpEndpoints(recipAddresses))
        {
            LOGGER.info("Recipients include SMTP endpoints");
            
            mail.setRecipients(getSmtpRecips(recipAddresses));
        }
        else
        {
            LOGGER.info("Recipients do not include SMTP endpoints");
            
            // No SMTP addresses, ghost it
            mail.setState(Mail.GHOST);
        }
    }

    private Collection<MailAddress> getSmtpRecips(Collection<String> recips) throws AddressException, MessagingException
    {
        List<MailAddress> addrs = new ArrayList<MailAddress>();

        for (String s : getResolver().getSmtpEndpoints(recips))
            addrs.add(new MailAddress(s));

        return addrs;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see org.apache.mailet.base.GenericMailet#init()
     */
    @Override
    public void init() throws MessagingException
    {

    	LOGGER.info("Initializing DirectXdMailet");
        super.init();
        
        // Get the endpoint URL
        endpointUrl = getInitParameter("EndpointURL");

        if (StringUtils.isBlank(endpointUrl))
        {
            LOGGER.error("DirectXdMailet endpoint URL cannot be empty or null.");
            throw new MessagingException("DirectXdMailet endpoint URL cannot be empty or null.");
        }
        
        notificationProducer = new ReliableDispatchedNotificationProducer(new NotificationSettings(true, "Direct XD Delivery Agent", "Your message was successfully dispatched."));
        
        deliveryCore = new XDDeliveryCore(getResolver(), this, this.txParser, 
        		getMimeXDSTransformer(), getDocumentRepository(), 
        		notificationProducer,  endpointUrl);
    }

    @Override
	protected ApplicationContext createSpringApplicationContext()
	{
		return new ClassPathXmlApplicationContext("contexts/XDMailet.xml");
	}
    
    /**
     * Return the value of endpointUrl.
     * 
     * @return the value of endpointUrl.
     */
    protected String getEndpointUrl()
    {
        return this.endpointUrl;
    }

    /**
     * Set the value of endpointUrl.
     * 
     * @param endpointUrl
     *            The value of endpointUrl.
     */
    protected void setEndpointUrl(String endpointUrl)
    {
        this.endpointUrl = endpointUrl;
    }


    /**
     * Set the value of resolver.
     * 
     * @param resolver
     *            The value of resolver.
     */
    protected void setResolver(RoutingResolver resolver)
    {
        this.resolver = resolver;
    }

    /**
     * Get the value of resolver.
     * 
     * @return the value of resolver.
     */
    protected RoutingResolver getResolver() throws MessagingException
    {
		if (ctx == null)
		{
			throw new MessagingException("Spring Application Context is null");
		}    	
    	
        if (this.resolver == null)
        {
            this.resolver = new RoutingResolverImpl(ctx.getBean(AddressService.class));
        }

        return resolver;
    }

    /**
     * Set the value of mimeXDSTransformer.
     * 
     * @param mimeXDSTransformer
     *            The value of mimeXDSTransformer.
     */
    protected void setMimeXDSTransformer(MimeXdsTransformer mimeXDSTransformer)
    {
        this.mimeXDSTransformer = mimeXDSTransformer;
    }

    /**
     * Get the value of mimeXDSTransfomer.
     * 
     * @return the value of mimeXDSTransformer, or a new object if null.
     */
    protected MimeXdsTransformer getMimeXDSTransformer()
    {
        if (this.mimeXDSTransformer == null)
        {
            this.mimeXDSTransformer = new DefaultMimeXdsTransformer();
        }

        return this.mimeXDSTransformer;
    }

    /**
     * Set the value of documentRepository.
     * 
     * @param documentRepository
     *            The value of documentRepository.
     */
    public void setDocumentRepository(DocumentRepository documentRepository)
    {
        this.documentRepository = documentRepository;
    }

    /**
     * Get the value of documentRepository.
     * 
     * @return the value of documentRepository, or a new object if null.
     */
    public DocumentRepository getDocumentRepository()
    {
        if (this.documentRepository == null)
        {
            this.documentRepository = new DocumentRepository();
        }

        return documentRepository;
    }
    
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected DSNCreator createDSNGenerator() 
	{
		return new FailedDeliveryDSNCreator(this);
	}    
	
	public void sendNotificationMessage(NotificationMessage message) throws MessagingException
	{
		try
		{
			getMailetContext().sendMail(message);
		}
		catch (Throwable t)
		{
			// don't kill the process if this fails
			LOGGER.error("Error sending MDN dispatched message.", t);
		}
	}
	
	public void sendFailureMessage(Tx tx, NHINDAddressCollection undeliveredRecipeints, boolean useSenderAsPostmaster) throws MessagingException
	{
		this.sendDSN(tx, undeliveredRecipeints, false);
	}
}
