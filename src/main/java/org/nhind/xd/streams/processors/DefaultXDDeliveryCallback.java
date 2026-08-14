package org.nhind.xd.streams.processors;

import java.util.Collection;

import org.nhind.mail.service.XDDeliveryCallback;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.gateway.smtp.dsn.DSNCreator;
import org.nhindirect.gateway.streams.SmtpGatewayMessageSource;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultXDDeliveryCallback implements XDDeliveryCallback {
	
	@Autowired
	protected SmtpGatewayMessageSource smtpMessageSource;
	
	@Autowired
	protected DSNCreator dsnCreator;
	
	
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
