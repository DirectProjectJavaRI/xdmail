package org.nhind.mail.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;

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
        final NHINDAddressCollection failedRecipients = new NHINDAddressCollection();
        final MimeMessage msg = smtpMailMessage.getMimeMessage();
        final String mimeMessageId = StringUtils.strip(msg.getMessageID(), "<>");

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
                final Collection<String> xdAddressStrings = resolver.getXdEndpoints(recipAddresses);
                for (String s : xdAddressStrings)
                    xdRecipients.add(new NHINDAddress(s));

                txToTrack = MessageUtils.getTxToTrack(msg, sender, xdRecipients, txParser);

                // Replace recipients with only XD* addresses
                msg.setRecipients(RecipientType.TO, xdRecipients.toArray(new Address[0]));

                // Transform MimeMessage into ProvideAndRegisterDocumentSetRequestType object
                ProvideAndRegisterDocumentSetRequestType request = mimeXDSTransformer.transform(msg);

                // Group XD addresses by their effective endpoint (custom per-address or global default)
                final Map<String, List<String>> endpointGroups = new LinkedHashMap<>();
                for (String xdAddr : xdAddressStrings)
                {
                    final String customEndpoint = resolver.resolve(xdAddr);
                    final String effectiveEndpoint = StringUtils.isNotEmpty(customEndpoint) ? customEndpoint : endpointUrl;
                    endpointGroups.computeIfAbsent(effectiveEndpoint, k -> new ArrayList<>()).add(xdAddr);
                }

                for (Map.Entry<String, List<String>> entry : endpointGroups.entrySet())
                {
                    final String groupEndpoint = entry.getKey();
                    final List<String> groupAddresses = entry.getValue();
                    final String groupDirectTo = String.join(",", groupAddresses);

                    String response = documentRepository.forwardRequest(groupEndpoint, request, groupDirectTo, sender.toString(), mimeMessageId);

                    if (!isSuccessful(response))
                    {
                        log.error("DirectXdMailet failed to deliver XD message.");
                        log.error(response);
                        for (String addr : groupAddresses)
                            failedRecipients.add(new NHINDAddress(addr));
                    }
                    else
                    {
                        successfulTransaction = true;
                        if (isReliableAndTimely && txToTrack != null && txToTrack.getMsgType() == TxMessageType.IMF)
                        {
                            // Send one MDN per address in this group
                            for (String addr : groupAddresses)
                            {
                                final NHINDAddressCollection singleRecipient = new NHINDAddressCollection();
                                singleRecipient.add(new NHINDAddress(addr));

                                final Collection<NotificationMessage> notifications =
                                        notificationProducer.produce(new Message(msg), singleRecipient.toInternetAddressCollection());
                                if (notifications != null && notifications.size() > 0)
                                {
                                    log.debug("Sending MDN \"dispathed\" messages");
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
            }
            catch (Throwable e)
            {
                log.error("DirectXdMailet delivery failure", e);
                // Any recipients not yet individually failed are treated as failed
                for (NHINDAddress addr : xdRecipients)
                {
                    if (!failedRecipients.contains(addr))
                        failedRecipients.add(addr);
                }
            }
        }

        if (!failedRecipients.isEmpty() && txToTrack != null && txToTrack.getMsgType() == TxMessageType.IMF)
        {
            callback.sendFailureMessage(txToTrack, failedRecipients, false);
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
