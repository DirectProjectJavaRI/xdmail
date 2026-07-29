package org.nhind.mail.service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.tx.TxDetailParser;
import org.nhindirect.common.tx.TxUtil;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.common.tx.model.TxDetail;
import org.nhindirect.common.tx.model.TxDetailType;
import org.nhindirect.common.tx.model.TxMessageType;
import org.nhindirect.gateway.smtp.NotificationProducer;
import org.nhindirect.gateway.util.MessageUtils;
import org.nhindirect.stagent.NHINDAddress;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.Message;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.nhindirect.xd.common.DirectDocument2;
import org.nhindirect.xd.common.DirectDocuments;
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

    protected final List<String> suppressNotificationAddresses;

    public XDDeliveryCore(RoutingResolver resolver, XDDeliveryCallback callback, TxDetailParser txParser,
            MimeXdsTransformer mimeXDSTransformer, DocumentRepository documentRepository,
            NotificationProducer notificationProducer, String endpointUrl,
            List<String> suppressNotificationAddresses)
    {
        this.resolver = resolver;
        this.callback = callback;
        this.txParser = txParser;
        this.mimeXDSTransformer = mimeXDSTransformer;
        this.endpointUrl = endpointUrl;
        this.documentRepository = documentRepository;
        this.notificationProducer = notificationProducer;
        this.suppressNotificationAddresses = suppressNotificationAddresses;
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

                // Notification messages (MDN/DSN) must not be run through the clinical document
                // transform - they are converted into a minimal XDR "notification" document instead,
                // per the XDR and XDM for Direct Messaging Specification.
                String notificationRelatesTo = null;
                final ProvideAndRegisterDocumentSetRequestType request;
                if (txToTrack != null &&
                        (txToTrack.getMsgType() == TxMessageType.MDN || txToTrack.getMsgType() == TxMessageType.DSN))
                {
                    log.info("Converting {} message into an XDR notification document", txToTrack.getMsgType());
                    notificationRelatesTo = getParentMessageId(txToTrack);
                    request = buildNotificationRequest(txToTrack, sender, xdRecipients);
                }
                else
                {
                    // Transform MimeMessage into ProvideAndRegisterDocumentSetRequestType object
                    request = mimeXDSTransformer.transform(msg);
                }

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

                    String response = documentRepository.forwardRequest(groupEndpoint, request, groupDirectTo, sender.toString(), mimeMessageId, notificationRelatesTo);

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
                            // Send one MDN per address in this group, excluding any address configured
                            // to have dispatched notifications suppressed
                            for (String addr : groupAddresses)
                            {
                                if (isAddressSuppressed(addr))
                                {
                                    log.debug("Dispatched MDN suppressed for configured address {}", addr);
                                    continue;
                                }

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
            // A DSN is always addressed back to the original sender, so suppression has to be decided
            // against the failed recipients themselves (the addresses this list is meant to target)
            // before the DSN is generated, not against the resulting DSN message's own headers.
            final NHINDAddressCollection notSuppressedFailedRecipients = new NHINDAddressCollection();
            for (NHINDAddress recip : failedRecipients)
            {
                if (isAddressSuppressed(recip.getAddress()))
                    log.debug("DSN failure notification suppressed for configured address {}", recip.getAddress());
                else
                    notSuppressedFailedRecipients.add(recip);
            }

            if (notSuppressedFailedRecipients.isEmpty())
                log.debug("All undelivered recipients are configured suppressed addresses; not generating a DSN failure notification");
            else
                callback.sendFailureMessage(txToTrack, notSuppressedFailedRecipients, false);
        }

        return successfulTransaction;
    }

    private boolean isSuccessful(String response)
    {
        if (StringUtils.contains(response, "Failure"))
            return false;

        return true;
    }

    /*
     * Tests if the given address matches an address in the configured suppression list.
     */
    protected boolean isAddressSuppressed(String rawAddress)
    {
        if (suppressNotificationAddresses == null || suppressNotificationAddresses.isEmpty() || rawAddress == null)
            return false;

        final String normalizedAddr = normalizeAddress(rawAddress);
        if (normalizedAddr == null)
            return false;

        for (String suppressAddr : suppressNotificationAddresses)
        {
            if (!suppressAddr.trim().isEmpty() && normalizedAddr.equalsIgnoreCase(suppressAddr.trim()))
                return true;
        }

        return false;
    }

    /*
     * Normalizes a message address for suppression comparison by lower casing it and stripping any
     * plus addressing tag (eg. gm2552+category@example.com becomes gm2552@example.com) from the
     * local part, so that plus addressed variants of a configured suppression address are also
     * suppressed. Configured suppression addresses are not plus-addressing normalized since they are
     * expected to already be canonical addresses.
     */
    protected static String normalizeAddress(String address)
    {
        if (address == null)
            return null;

        final String trimmedAddr = address.trim();
        if (trimmedAddr.isEmpty())
            return null;

        final int atIdx = trimmedAddr.indexOf('@');
        if (atIdx < 0)
            return trimmedAddr.toLowerCase();

        String localPart = trimmedAddr.substring(0, atIdx);
        final String domainPart = trimmedAddr.substring(atIdx);

        final int plusIdx = localPart.indexOf('+');
        if (plusIdx >= 0)
            localPart = localPart.substring(0, plusIdx);

        return (localPart + domainPart).toLowerCase();
    }

    /**
     * Gets the message id of the original message that an MDN or DSN message corresponds to, stripped
     * of enclosing angle brackets, for use as the direct:notification relatesTo attribute value.
     */
    private static String getParentMessageId(Tx txToTrack)
    {
        final TxDetail detail = txToTrack.getDetail(TxDetailType.PARENT_MSG_ID);

        return (detail == null) ? null : StringUtils.strip(detail.getDetailValue(), "<>");
    }

    /**
     * Extracts the recipient address that an MDN/DSN's FINAL_RECIPIENTS detail pertains to. MDN
     * messages retain an "rfc822;" prefix on the Final-Recipient value; DSN messages already have it
     * stripped and may contain a comma delimited list of addresses. Either way, only the first address
     * is relevant for the notification document.
     */
    private static String extractNotificationRecipient(Tx txToTrack)
    {
        final TxDetail detail = txToTrack.getDetail(TxDetailType.FINAL_RECIPIENTS);
        if (detail == null || StringUtils.isBlank(detail.getDetailValue()))
            return "";

        String recipient = StringUtils.substringBefore(detail.getDetailValue(), ",").trim();
        if (StringUtils.startsWithIgnoreCase(recipient, "rfc822;"))
            recipient = recipient.substring("rfc822;".length()).trim();

        return recipient;
    }

    /**
     * Builds a minimal ProvideAndRegisterDocumentSetRequestType wrapping a direct:messageDisposition
     * document, per the XDR and XDM for Direct Messaging Specification's notification message format.
     */
    private ProvideAndRegisterDocumentSetRequestType buildNotificationRequest(Tx txToTrack, NHINDAddress sender,
            NHINDAddressCollection xdRecipients) throws IOException
    {
        final String recipient = extractNotificationRecipient(txToTrack);
        final String disposition = (txToTrack.getMsgType() == TxMessageType.MDN) ? "success" : "failure";

        final String dispositionXml = "<direct:messageDisposition xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:direct=\"urn:direct:addressing\">\r\n"
                + "    <direct:recipient>mailto:" + recipient + "</direct:recipient>\r\n"
                + "    <direct:disposition>" + disposition + "</direct:disposition>\r\n"
                + "</direct:messageDisposition>";

        final DirectDocument2 document = new DirectDocument2();
        document.getMetadata().setMimeType("text/xml");
        document.getMetadata().setUniqueId(generateOid());
        document.setData(dispositionXml.getBytes(StandardCharsets.UTF_8));

        final DirectDocuments documents = new DirectDocuments();
        documents.getDocuments().add(document);

        final DirectDocuments.SubmissionSet submissionSet = documents.getSubmissionSet();
        submissionSet.setAuthorTelecommunication(sender.toString());
        submissionSet.setSourceId(sender.getAddress());
        submissionSet.setSubmissionTime(new Date());
        submissionSet.setUniqueId(generateOid());
        for (NHINDAddress addr : xdRecipients)
            submissionSet.getIntendedRecipient().add("||^^Internet^" + addr.getAddress());

        return documents.toProvideAndRegisterDocumentSetRequestType();
    }

    private static String generateOid()
    {
        final UUID uuid = UUID.randomUUID();
        return "2.25." + new BigInteger(uuid.toString().replace("-", ""), 16);
    }
}
