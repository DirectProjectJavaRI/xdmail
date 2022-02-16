package org.nhind.xd.springconfig;

import org.nhind.mail.service.DocumentRepository;
import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhindirect.common.tx.impl.DefaultTxDetailParser;
import org.nhindirect.gateway.smtp.NotificationSettings;
import org.nhindirect.gateway.smtp.ReliableDispatchedNotificationProducer;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.transform.impl.DefaultMimeXdsTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XDDeliveryConfig
{
	@Value("${direct.gateway.xd.endpointUrl: http://localhost:8080/xd/services/DocumentRepository_Service}")
	protected String endpointURL;
	
	@Autowired
	protected RoutingResolver resolver;
	
	@Autowired
	protected XDDeliveryCallback xdDeliveryCallback;
	
	@Bean
	@ConditionalOnMissingBean
	public XDDeliveryCore xdDeliveryCore()
	{
		final ReliableDispatchedNotificationProducer notificationProducer = 
				new ReliableDispatchedNotificationProducer(new NotificationSettings(true, "Direct XD Delivery Agent", "Your message was successfully dispatched."));
		
		return new XDDeliveryCore(resolver, xdDeliveryCallback, new DefaultTxDetailParser(), 
				new DefaultMimeXdsTransformer(), new DocumentRepository(), notificationProducer, endpointURL);
	}	
}
