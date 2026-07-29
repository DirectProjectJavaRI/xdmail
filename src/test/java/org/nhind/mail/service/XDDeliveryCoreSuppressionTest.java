package org.nhind.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.tx.TxDetailParser;
import org.nhindirect.gateway.smtp.NotificationProducer;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.transform.MimeXdsTransformer;

import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;

/**
 * Tests for the {@code direct.gateway.xd.notifications.suppressNotificationsForAddresses} suppression
 * list in {@link XDDeliveryCore#processAndDeliverXDMessage}. Both the dispatched MDN (on successful XDR
 * delivery) and the DSN (on failed XDR delivery) generation paths are only reached for messages that are
 * not themselves an MDN or DSN (they are gated on {@code TxMessageType.IMF}, as covered separately by
 * {@link XDDeliveryCoreNotificationTest}), so suppression only ever needs to be evaluated there.
 */
class XDDeliveryCoreSuppressionTest
{
    private static final String XDR_ENDPOINT = "https://xdr.example.org/Repository";
    private static final String SENDER = "sender@senderdomain.example.org";
    private static final String SUPPRESSED_RECIPIENT = "alice@xddomain.example.org";
    private static final String NOT_SUPPRESSED_RECIPIENT = "bob@xddomain.example.org";

    private RoutingResolver resolver;
    private XDDeliveryCallback callback;
    private TxDetailParser txParser;
    private MimeXdsTransformer mimeXdsTransformer;
    private DocumentRepository documentRepository;
    private NotificationProducer notificationProducer;

    @BeforeEach
    void setUp() throws Exception
    {
        resolver = mock(RoutingResolver.class);
        callback = mock(XDDeliveryCallback.class);
        txParser = mock(TxDetailParser.class);
        mimeXdsTransformer = mock(MimeXdsTransformer.class);
        documentRepository = mock(DocumentRepository.class);
        notificationProducer = mock(NotificationProducer.class);

        when(txParser.getMessageDetails(any(MimeMessage.class))).thenReturn(new HashMap<>());
        when(resolver.resolve(anyString())).thenReturn(null);
        when(mimeXdsTransformer.transform(any())).thenReturn(new ProvideAndRegisterDocumentSetRequestType());
        when(notificationProducer.produce(any(), anyCollection()))
                .thenReturn(List.of(mock(NotificationMessage.class)));
    }

    private XDDeliveryCore buildDeliveryCore(List<String> suppressNotificationAddresses)
    {
        return new XDDeliveryCore(resolver, callback, txParser, mimeXdsTransformer, documentRepository,
                notificationProducer, XDR_ENDPOINT, suppressNotificationAddresses);
    }

    @Test
    void dispatchedMdn_recipientMatchesSuppressList_assertNotSent() throws Exception
    {
        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection())).thenReturn(Arrays.asList(SUPPRESSED_RECIPIENT));
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any()))
                        .thenReturn("urn:oasis:names:tc:ebxml-regrep:ResponseStatusType:Success");

        final XDDeliveryCore deliveryCore = buildDeliveryCore(Arrays.asList(SUPPRESSED_RECIPIENT));

        final MimeMessage msg = buildImfMessage(SUPPRESSED_RECIPIENT, true);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();
        verify(notificationProducer, never()).produce(any(), anyCollection());
        verify(callback, never()).sendNotificationMessage(any());
    }

    @Test
    void dispatchedMdn_recipientIsPlusAddressedVariantOfSuppressedAddress_assertNotSent() throws Exception
    {
        final String plusAddressedRecipient = "alice+testing@xddomain.example.org";

        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection())).thenReturn(Arrays.asList(plusAddressedRecipient));
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any()))
                        .thenReturn("urn:oasis:names:tc:ebxml-regrep:ResponseStatusType:Success");

        final XDDeliveryCore deliveryCore = buildDeliveryCore(Arrays.asList(SUPPRESSED_RECIPIENT));

        final MimeMessage msg = buildImfMessage(plusAddressedRecipient, true);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();
        verify(notificationProducer, never()).produce(any(), anyCollection());
        verify(callback, never()).sendNotificationMessage(any());
    }

    @Test
    void dispatchedMdn_onlySuppressedRecipientExcluded_assertOtherRecipientStillSent() throws Exception
    {
        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection()))
                .thenReturn(Arrays.asList(SUPPRESSED_RECIPIENT, NOT_SUPPRESSED_RECIPIENT));
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any()))
                        .thenReturn("urn:oasis:names:tc:ebxml-regrep:ResponseStatusType:Success");

        final XDDeliveryCore deliveryCore = buildDeliveryCore(Arrays.asList(SUPPRESSED_RECIPIENT));

        final MimeMessage msg = buildImfMessage(SUPPRESSED_RECIPIENT + "," + NOT_SUPPRESSED_RECIPIENT, true);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();

        final ArgumentCaptor<java.util.Collection<InternetAddress>> recipsCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(notificationProducer, times(1)).produce(any(), recipsCaptor.capture());
        assertThat(recipsCaptor.getValue()).extracting(InternetAddress::getAddress)
                .containsExactly(NOT_SUPPRESSED_RECIPIENT);

        verify(callback, times(1)).sendNotificationMessage(any());
    }

    @Test
    void dsnFailure_recipientMatchesSuppressList_assertNotSent() throws Exception
    {
        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection())).thenReturn(Arrays.asList(SUPPRESSED_RECIPIENT));
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any())).thenReturn("Failure");

        final XDDeliveryCore deliveryCore = buildDeliveryCore(Arrays.asList(SUPPRESSED_RECIPIENT));

        final MimeMessage msg = buildImfMessage(SUPPRESSED_RECIPIENT, false);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isFalse();
        verify(callback, never()).sendFailureMessage(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void dsnFailure_onlySuppressedRecipientExcluded_assertOtherRecipientStillReported() throws Exception
    {
        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection()))
                .thenReturn(Arrays.asList(SUPPRESSED_RECIPIENT, NOT_SUPPRESSED_RECIPIENT));
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any())).thenReturn("Failure");

        final XDDeliveryCore deliveryCore = buildDeliveryCore(Arrays.asList(SUPPRESSED_RECIPIENT));

        final MimeMessage msg = buildImfMessage(SUPPRESSED_RECIPIENT + "," + NOT_SUPPRESSED_RECIPIENT, false);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isFalse();

        final ArgumentCaptor<NHINDAddressCollection> failedCaptor = ArgumentCaptor.forClass(NHINDAddressCollection.class);
        verify(callback, times(1)).sendFailureMessage(any(), failedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(false));

        assertThat(failedCaptor.getValue()).extracting(a -> a.getAddress()).containsExactly(NOT_SUPPRESSED_RECIPIENT);
    }

    @Test
    void emptySuppressList_dispatchedMdnStillSent() throws Exception
    {
        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection())).thenReturn(Arrays.asList(NOT_SUPPRESSED_RECIPIENT));
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any()))
                        .thenReturn("urn:oasis:names:tc:ebxml-regrep:ResponseStatusType:Success");

        final XDDeliveryCore deliveryCore = buildDeliveryCore(Collections.emptyList());

        final MimeMessage msg = buildImfMessage(NOT_SUPPRESSED_RECIPIENT, true);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();
        verify(callback, times(1)).sendNotificationMessage(any());
    }

    private static SMTPMailMessage buildSmtpMessage(MimeMessage msg) throws Exception
    {
        final InternetAddress from = (InternetAddress) msg.getFrom()[0];
        final List<InternetAddress> recipients = Arrays.stream(msg.getAllRecipients())
                .map(a -> (InternetAddress) a).collect(java.util.stream.Collectors.toList());

        return new SMTPMailMessage(msg, recipients, from);
    }

    /*
     * Builds a plain IMF message (not an MDN or DSN) addressed to the given comma delimited recipient
     * list. When reliableAndTimely is true, a Disposition-Notification-Options header requesting the
     * X-DIRECT-FINAL-DESTINATION-DELIVERY option is set so that TxUtil.isReliableAndTimelyRequested(...)
     * returns true and a dispatched MDN is produced on successful delivery.
     */
    private static MimeMessage buildImfMessage(String toRecipients, boolean reliableAndTimely) throws Exception
    {
        final String extra = reliableAndTimely ? "Disposition-Notification-Options: X-DIRECT-FINAL-DESTINATION-DELIVERY\r\n" : "";

        final String raw = "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "From: " + SENDER + "\r\n"
                + "To: " + toRecipients + "\r\n"
                + "Subject: Normal Message\r\n"
                + "Message-ID: <imf-message-id@senderdomain.example.org>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain\r\n"
                + extra
                + "\r\n"
                + "Hello world.\r\n";

        return new MimeMessage(null, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
