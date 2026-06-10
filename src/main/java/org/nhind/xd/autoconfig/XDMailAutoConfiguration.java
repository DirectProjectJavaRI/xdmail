package org.nhind.xd.autoconfig;

import org.nhind.mail.service.DocumentRepository;
import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhind.xd.streams.processors.DefaultXDDeliveryCallback;
import org.nhind.xd.streams.processors.XDRemoteDeliveryProcessor;
import org.nhindirect.common.tx.impl.DefaultTxDetailParser;
import org.nhindirect.gateway.smtp.NotificationSettings;
import org.nhindirect.gateway.smtp.ReliableDispatchedNotificationProducer;
import org.nhindirect.xd.common.SyntheticMetadataDefaults;
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
	@Value("${direct.gateway.xd.endpointUrl:}")
	protected String endpointURL;

	@Value("${direct.xd.documents.syntheticdata.classCode:" + SyntheticMetadataDefaults.DEFAULT_CLASS_CODE + "}")
	protected String syntheticClassCode;

	@Value("${direct.xd.documents.syntheticdata.confidentialityCode:" + SyntheticMetadataDefaults.DEFAULT_CONFIDENTIALITY_CODE + "}")
	protected String syntheticConfidentialityCode;

	@Value("${direct.xd.documents.syntheticdata.healthcareFacilityTypeCode:" + SyntheticMetadataDefaults.DEFAULT_HEALTHCARE_FACILITY_TYPE_CODE + "}")
	protected String syntheticHealthcareFacilityTypeCode;

	@Value("${direct.xd.documents.syntheticdata.practiceSettingCode:" + SyntheticMetadataDefaults.DEFAULT_PRACTICE_SETTING_CODE + "}")
	protected String syntheticPracticeSettingCode;

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
		
		SyntheticMetadataDefaults syntheticDefaults = new SyntheticMetadataDefaults(
				syntheticClassCode, syntheticConfidentialityCode,
				syntheticHealthcareFacilityTypeCode, syntheticPracticeSettingCode);

		return new XDDeliveryCore(resolver, xdDeliveryCallback, new DefaultTxDetailParser(),
				new DefaultMimeXdsTransformer(syntheticDefaults), new DocumentRepository(), notificationProducer, endpointURL);
	}	
}
