package org.nhind.xd.streams.processors;

import java.util.Collection;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhind.xd.streams.XDRemoteDeliveryInput;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.mail.streams.SMTPMailMessageConverter;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.gateway.smtp.dsn.DSNCreator;
import org.nhindirect.gateway.streams.SmtpGatewayMessageSource;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.Message;

@EnableBinding(XDRemoteDeliveryInput.class)
public class XDRemoteDeliveryProcessor implements XDDeliveryCallback
{
	@Autowired
	protected XDDeliveryCore deliveryCore;
	
	@Autowired
	protected SmtpGatewayMessageSource smtpMessageSource;
	
	@Autowired
	protected DSNCreator dsnCreator;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(XDRemoteDeliveryProcessor.class);	
	
	@StreamListener(target = XDRemoteDeliveryInput.XD_DELIVERY_MESSAGE_INPUT)
	public void xdRemotelyDeliverMessage(Message<?> streamMsg) throws MessagingException
	{
		final SMTPMailMessage smtpMessage = SMTPMailMessageConverter.fromStreamMessage(streamMsg);
        
        deliveryCore.processAndDeliverXDMessage(smtpMessage);
	}
	
	public void sendNotificationMessage(NotificationMessage message) throws MessagingException
	{
		smtpMessageSource.sendMimeMessage(message);
	}
	
	public void sendFailureMessage(Tx tx, NHINDAddressCollection undeliveredRecipeints, boolean useSenderAsPostmaster) throws MessagingException
	{
		try
		{
			final Collection<MimeMessage> msgs = dsnCreator.createDSNFailure(tx, undeliveredRecipeints, useSenderAsPostmaster);
			if (msgs != null && msgs.size() > 0)
				for (MimeMessage msg : msgs)
					smtpMessageSource.sendMimeMessage(msg);
		}
		catch (Throwable e)
		{
			// don't kill the process if this fails
			LOGGER.error("Error sending DSN failure message.", e);
		}
	}	
}
