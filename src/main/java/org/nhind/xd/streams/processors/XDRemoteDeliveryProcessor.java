package org.nhind.xd.streams.processors;

import java.util.function.Consumer;

import jakarta.mail.MessagingException;

import org.nhind.mail.service.XDDeliveryCore;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.mail.streams.SMTPMailMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;


@Configuration
public class XDRemoteDeliveryProcessor
{
	@Autowired
	protected XDDeliveryCore deliveryCore;
	
	@Bean
	public Consumer<Message<?>> directXDDeliveryInput()
	{
		return streamMsg -> 
		{
			try
			{
				final SMTPMailMessage smtpMessage = SMTPMailMessageConverter.fromStreamMessage(streamMsg);
		        
		        deliveryCore.processAndDeliverXDMessage(smtpMessage);
			}
			catch (MessagingException e)
			{
				throw new RuntimeException(e);
			}
		};
	}	
}
