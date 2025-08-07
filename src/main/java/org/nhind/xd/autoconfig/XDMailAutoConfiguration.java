package org.nhind.xd.autoconfig;

import org.nhind.mail.service.DocumentRepository;
import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhind.xd.streams.processors.DefaultXDDeliveryCallback;
import org.nhind.xd.streams.processors.XDRemoteDeliveryProcessor;
import org.nhindirect.common.tx.impl.DefaultTxDetailParser;
import org.nhindirect.gateway.smtp.NotificationSettings;
import org.nhindirect.gateway.smtp.ReliableDispatchedNotificationProducer;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.transform.impl.DefaultMimeXdsTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(XDRemoteDeliveryProcessor.class)
public class XDMailAutoConfiguration
{
	@Value("direct.gateway.xd.endpointUrl")
	protected String endpointURL;
	
	@Autowired
	protected RoutingResolver resolver;
	
	@Bean
	@ConditionalOnMissingBean
    XDDeliveryCallback xdDeliveryCallback() {
		
		return new DefaultXDDeliveryCallback();
		
	}
	
	@Bean
	@ConditionalOnMissingBean
	XDDeliveryCore xdDeliveryCore(XDDeliveryCallback xdDeliveryCallback)
	{
		final ReliableDispatchedNotificationProducer notificationProducer = 
				new ReliableDispatchedNotificationProducer(new NotificationSettings(true, "Direct XD Delivery Agent", "Your message was successfully dispatched."));
		
		return new XDDeliveryCore(resolver, xdDeliveryCallback, new DefaultTxDetailParser(), 
				new DefaultMimeXdsTransformer(), new DocumentRepository(), notificationProducer, endpointURL);
	}	
}
