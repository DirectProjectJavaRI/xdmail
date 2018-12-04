package org.nhind.mail.service;

import javax.mail.MessagingException;

import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;

public interface XDDeliveryCallback
{
	public void sendNotificationMessage(NotificationMessage message) throws MessagingException;
	
	public void sendFailureMessage(Tx tx, NHINDAddressCollection undeliveredRecipeints, boolean useSenderAsPostmaster) throws MessagingException;
}
