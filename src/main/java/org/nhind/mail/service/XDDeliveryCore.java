package org.nhind.mail.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Message.RecipientType;
import javax.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.tx.TxDetailParser;
import org.nhindirect.common.tx.TxUtil;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.common.tx.model.TxMessageType;
import org.nhindirect.gateway.smtp.NotificationProducer;
import org.nhindirect.gateway.util.MessageUtils;
import org.nhindirect.stagent.NHINDAddress;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.Message;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.transform.MimeXdsTransformer;

import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XDDeliveryCore
{

	protected final RoutingResolver resolver;
	
	protected final XDDeliveryCallback callback;
	
	protected final TxDetailParser txParser;
	
	protected final MimeXdsTransformer mimeXDSTransformer;
	
	protected final DocumentRepository documentRepository;
	
	protected final NotificationProducer notificationProducer;
	
	protected final String endpointUrl;
	
	public XDDeliveryCore(RoutingResolver resolver, XDDeliveryCallback callback, TxDetailParser txParser, 
			MimeXdsTransformer mimeXDSTransformer, DocumentRepository documentRepository, 
			NotificationProducer notificationProducer, String endpointUrl)
	{
		this.resolver = resolver;
		this.callback = callback;
		this.txParser = txParser;
		this.mimeXDSTransformer = mimeXDSTransformer;
		this.endpointUrl = endpointUrl;
		this.documentRepository = documentRepository;
		this.notificationProducer = notificationProducer;
	}
	
	public boolean processAndDeliverXDMessage(SMTPMailMessage smtpMailMessage) throws MessagingException
	{
        log.info("Servicing process XD Message.");

        boolean successfulTransaction = false;
		final boolean isReliableAndTimely = TxUtil.isReliableAndTimelyRequested(smtpMailMessage.getMimeMessage());
		
		
		final NHINDAddressCollection initialRecipients = MessageUtils.getMailRecipients(smtpMailMessage);
		final NHINDAddressCollection xdRecipients = new NHINDAddressCollection();					
		final MimeMessage msg = smtpMailMessage.getMimeMessage();
		
		final NHINDAddress sender = MessageUtils.getMailSender(smtpMailMessage);
		Tx txToTrack = null;
		
        // Get recipients and create a collection of Strings
        final List<String> recipAddresses = new ArrayList<String>();

        for (NHINDAddress addr : initialRecipients)
        {
            recipAddresses.add(addr.getAddress());
        }

        // Service XD* addresses
       if (resolver.hasXdEndpoints(recipAddresses))
        {
            log.info("Recipients include XD endpoints");
            
            try
            {
                // Extract XD* addresses
                //List<Address> xdAddresses = new ArrayList<Address>();
                for (String s : resolver.getXdEndpoints(recipAddresses))
                    xdRecipients.add(new NHINDAddress(s));

                txToTrack = MessageUtils.getTxToTrack(msg, sender, xdRecipients, txParser);
                
                // Replace recipients with only XD* addresses
                msg.setRecipients(RecipientType.TO, xdRecipients.toArray(new Address[0]));

                // Transform MimeMessage into ProvideAndRegisterDocumentSetRequestType object
                ProvideAndRegisterDocumentSetRequestType request = mimeXDSTransformer.transform(msg);

                for (String directTo : recipAddresses)
                {
                    log.debug("GOING TO SEND TO " + resolver.getXdEndpoints(recipAddresses).toString() + " OVER " + endpointUrl);
                    String response = documentRepository.forwardRequest(endpointUrl, request, directTo, sender.toString());

                    if (!isSuccessful(response))
                    {
                        log.error("DirectXdMailet failed to deliver XD message.");
                        log.error(response);
                        
                    }
                    else
                    {
                    	successfulTransaction = true;
	                    if (isReliableAndTimely && txToTrack != null && txToTrack.getMsgType() == TxMessageType.IMF)
	                    {
	
	                    	// send MDN dispatch for messages the recipients that were successful
	        				final Collection<NotificationMessage> notifications = 
	        						notificationProducer.produce(new Message(msg), xdRecipients.toInternetAddressCollection());
	        				if (notifications != null && notifications.size() > 0)
	        				{
	        					log.debug("Sending MDN \"dispathed\" messages");
	        					// create a message for each notification and put it on James "stack"
	        					for (NotificationMessage message : notifications)
	        					{
	        						try
	        						{
	        							callback.sendNotificationMessage(message);
	        						}
	        						catch (Throwable t)
	        						{
	        							// don't kill the process if this fails
	        							log.error("Error sending MDN dispatched message.", t);
	        						}
	        					}
	        				}
	                    }
                    }
                }
            }
            catch (Throwable e)
            {
                log.error("DirectXdMailet delivery failure", e);
            }
        }
        
        if (!successfulTransaction )
        {
        	if (txToTrack != null && txToTrack.getMsgType() == TxMessageType.IMF)
        	{
	        	// for good measure, send DSN messages back to the original sender on failure
				// create a DSN message
				callback.sendFailureMessage(txToTrack, xdRecipients, false);
        	}
        }
        
        return successfulTransaction;
	}
	
    private boolean isSuccessful(String response)
    {
        if (StringUtils.contains(response, "Failure"))
            return false;

        return true;
    }	
}
