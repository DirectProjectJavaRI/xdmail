package org.nhind.xd.streams;

import org.springframework.cloud.stream.annotation.Input;
import org.springframework.messaging.SubscribableChannel;

public interface XDRemoteDeliveryInput
{
	public static final String XD_DELIVERY_MESSAGE_INPUT = "direct-xd-delivery-input";
	
	@Input(XD_DELIVERY_MESSAGE_INPUT)
	SubscribableChannel xdRemoteDeliveryInput();
}
