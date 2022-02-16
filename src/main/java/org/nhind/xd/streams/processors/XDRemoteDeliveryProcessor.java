package org.nhind.xd.streams.processors;

import java.util.Collection;
import java.util.function.Consumer;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.mail.streams.SMTPMailMessageConverter;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.gateway.smtp.dsn.DSNCreator;
import org.nhindirect.gateway.streams.SmtpGatewayMessageSource;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class XDRemoteDeliveryProcessor implements XDDeliveryCallback
{
	@Autowired
	protected XDDeliveryCore deliveryCore;
	
	@Autowired
	protected SmtpGatewayMessageSource smtpMessageSource;
	
	@Autowired
	protected DSNCreator dsnCreator;
	
	@Bean
	public Consumer<Message<?>> directXDDeliveryInput()
	{
		return streamMsg -> 
		{
			try
			{
				final SMTPMailMessage smtpMessage = SMTPMailMessageConverter.fromStreamMessage(streamMsg);

            log.debug("XDRemoteDeliveryProcessor processing message from " + smtpMessage.getMailFrom().toString());
            deliveryCore.processAndDeliverXDMessage(smtpMessage);
			}
			catch (MessagingException e)
			{
				throw new RuntimeException(e);
			}
		};
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
			log.error("Error sending DSN failure message.", e);
		}
	}	
}
