package org.nhind.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.tx.TxDetailParser;
import org.nhindirect.common.tx.model.TxDetail;
import org.nhindirect.common.tx.model.TxDetailType;
import org.nhindirect.gateway.smtp.NotificationProducer;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.transform.MimeXdsTransformer;

import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;

/**
 * Tests for the MDN/DSN notification branch in {@link XDDeliveryCore#processAndDeliverXDMessage}.
 * <p>
 * {@link TxDetailParser} is mocked to return canned {@link TxDetail} maps per test, matching how MDN
 * vs. DSN messages actually represent PARENT_MSG_ID/FINAL_RECIPIENTS differently (angle-bracketed vs.
 * not, rfc822-prefixed vs. stripped, single value vs. comma delimited) - the parser is what shields
 * {@link XDDeliveryCore} from needing to know those format differences, so these tests assert that
 * shielding holds rather than re-testing {@code DefaultTxDetailParser}'s own field extraction.
 */
class XDDeliveryCoreNotificationTest
{
    private static final String XDR_ENDPOINT = "https://xdr.example.org/Repository";
    private static final String XD_RECIPIENT = "xdrecipient@xddomain.example.org";
    private static final String SENDER = "sender@senderdomain.example.org";
    private static final String NOTIFIER = "notifier@notifierdomain.example.org";

    private RoutingResolver resolver;
    private XDDeliveryCallback callback;
    private TxDetailParser txParser;
    private MimeXdsTransformer mimeXdsTransformer;
    private DocumentRepository documentRepository;
    private NotificationProducer notificationProducer;

    private XDDeliveryCore deliveryCore;

    @BeforeEach
    void setUp() throws Exception
    {
        resolver = mock(RoutingResolver.class);
        callback = mock(XDDeliveryCallback.class);
        txParser = mock(TxDetailParser.class);
        mimeXdsTransformer = mock(MimeXdsTransformer.class);
        documentRepository = mock(DocumentRepository.class);
        notificationProducer = mock(NotificationProducer.class);

        when(resolver.hasXdEndpoints(anyCollection())).thenReturn(true);
        when(resolver.getXdEndpoints(anyCollection())).thenReturn(Arrays.asList(XD_RECIPIENT));
        when(resolver.resolve(anyString())).thenReturn(null);

        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any()))
                        .thenReturn("urn:oasis:names:tc:ebxml-regrep:ResponseStatusType:Success");

        deliveryCore = new XDDeliveryCore(resolver, callback, txParser, mimeXdsTransformer, documentRepository,
                notificationProducer, XDR_ENDPOINT, List.of());
    }

    @Test
    void mdnMessage_isConvertedToXdrNotification_withSuccessDisposition() throws Exception
    {
        // MDN detail format: Final-Recipient keeps its "rfc822;" prefix, and PARENT_MSG_ID keeps
        // its enclosing "<>" (per DefaultTxDetailParser's MDN handling), and the message also
        // requests reliable-and-timely delivery to prove that doesn't cause a dispatched MDN here.
        final MimeMessage msg = buildMdnMessage(true);
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final Map<String, TxDetail> details = new HashMap<>();
        details.put(TxDetailType.PARENT_MSG_ID.getType(),
                new TxDetail(TxDetailType.PARENT_MSG_ID, "<parent-msg-id@senderdomain.example.org>"));
        details.put(TxDetailType.FINAL_RECIPIENTS.getType(),
                new TxDetail(TxDetailType.FINAL_RECIPIENTS, "rfc822;bob@example.com"));
        when(txParser.getMessageDetails(any(MimeMessage.class))).thenReturn(details);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();
        verify(mimeXdsTransformer, never()).transform(any());

        final ArgumentCaptor<ProvideAndRegisterDocumentSetRequestType> requestCaptor =
                ArgumentCaptor.forClass(ProvideAndRegisterDocumentSetRequestType.class);
        final ArgumentCaptor<String> relatesToCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).forwardRequest(anyString(), requestCaptor.capture(), anyString(), anyString(),
                anyString(), relatesToCaptor.capture());

        assertThat(relatesToCaptor.getValue()).isEqualTo("parent-msg-id@senderdomain.example.org");

        final String documentXml = readDocument(requestCaptor.getValue());
        assertThat(documentXml).contains("<direct:recipient>mailto:bob@example.com</direct:recipient>");
        assertThat(documentXml).contains("<direct:disposition>success</direct:disposition>");

        verifyNoInteractions(notificationProducer);
        verifyNoInteractions(callback);
    }

    @Test
    void dsnMessage_isConvertedToXdrNotification_withFailureDisposition_andCommaDelimitedRecipientList()
            throws Exception
    {
        // DSN detail format: FINAL_RECIPIENTS is already stripped of "rfc822;" and may be a comma
        // delimited list (DSNStandard.getFinalRecipients), and PARENT_MSG_ID has no "<>" wrapper.
        final MimeMessage msg = buildDsnMessage();
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final Map<String, TxDetail> details = new HashMap<>();
        details.put(TxDetailType.PARENT_MSG_ID.getType(),
                new TxDetail(TxDetailType.PARENT_MSG_ID, "parent-msg-id@senderdomain.example.org"));
        details.put(TxDetailType.FINAL_RECIPIENTS.getType(),
                new TxDetail(TxDetailType.FINAL_RECIPIENTS, "bob@example.com,carol@example.com"));
        when(txParser.getMessageDetails(any(MimeMessage.class))).thenReturn(details);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();
        verify(mimeXdsTransformer, never()).transform(any());

        final ArgumentCaptor<ProvideAndRegisterDocumentSetRequestType> requestCaptor =
                ArgumentCaptor.forClass(ProvideAndRegisterDocumentSetRequestType.class);
        final ArgumentCaptor<String> relatesToCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).forwardRequest(anyString(), requestCaptor.capture(), anyString(), anyString(),
                anyString(), relatesToCaptor.capture());

        assertThat(relatesToCaptor.getValue()).isEqualTo("parent-msg-id@senderdomain.example.org");

        final String documentXml = readDocument(requestCaptor.getValue());
        assertThat(documentXml).contains("<direct:recipient>mailto:bob@example.com</direct:recipient>");
        assertThat(documentXml).contains("<direct:disposition>failure</direct:disposition>");

        verifyNoInteractions(notificationProducer);
        verifyNoInteractions(callback);
    }

    @Test
    void dsnNotification_deliveryFailure_doesNotTriggerFailureCallback() throws Exception
    {
        when(documentRepository.forwardRequest(anyString(), any(ProvideAndRegisterDocumentSetRequestType.class),
                anyString(), anyString(), anyString(), any())).thenReturn("Failure");

        final MimeMessage msg = buildDsnMessage();
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        final Map<String, TxDetail> details = new HashMap<>();
        details.put(TxDetailType.PARENT_MSG_ID.getType(),
                new TxDetail(TxDetailType.PARENT_MSG_ID, "parent-msg-id@senderdomain.example.org"));
        details.put(TxDetailType.FINAL_RECIPIENTS.getType(),
                new TxDetail(TxDetailType.FINAL_RECIPIENTS, "bob@example.com"));
        when(txParser.getMessageDetails(any(MimeMessage.class))).thenReturn(details);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isFalse();
        verify(callback, never()).sendFailureMessage(any(), any(), anyBoolean());
    }

    @Test
    void imfMessage_stillUsesMimeXdsTransformer_notConvertedToNotification() throws Exception
    {
        final MimeMessage msg = buildImfMessage();
        final SMTPMailMessage smtpMailMessage = buildSmtpMessage(msg);

        when(txParser.getMessageDetails(any(MimeMessage.class))).thenReturn(new HashMap<>());

        final ProvideAndRegisterDocumentSetRequestType transformedRequest =
                new ProvideAndRegisterDocumentSetRequestType();
        when(mimeXdsTransformer.transform(any())).thenReturn(transformedRequest);

        final boolean result = deliveryCore.processAndDeliverXDMessage(smtpMailMessage);

        assertThat(result).isTrue();
        verify(mimeXdsTransformer, times(1)).transform(any(MimeMessage.class));

        final ArgumentCaptor<ProvideAndRegisterDocumentSetRequestType> requestCaptor =
                ArgumentCaptor.forClass(ProvideAndRegisterDocumentSetRequestType.class);
        final ArgumentCaptor<String> relatesToCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).forwardRequest(anyString(), requestCaptor.capture(), anyString(), anyString(),
                anyString(), relatesToCaptor.capture());

        assertThat(requestCaptor.getValue()).isSameAs(transformedRequest);
        assertThat(relatesToCaptor.getValue()).isNull();
    }

    private static String readDocument(ProvideAndRegisterDocumentSetRequestType request) throws Exception
    {
        final ProvideAndRegisterDocumentSetRequestType.Document document = request.getDocument().get(0);
        try (InputStream in = document.getValue().getInputStream())
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SMTPMailMessage buildSmtpMessage(MimeMessage msg) throws Exception
    {
        final InternetAddress from = (InternetAddress) msg.getFrom()[0];
        final List<InternetAddress> recipients = Arrays.stream(msg.getAllRecipients())
                .map(a -> (InternetAddress) a).collect(Collectors.toList());

        return new SMTPMailMessage(msg, recipients, from);
    }

    private static MimeMessage buildMdnMessage(boolean reliableAndTimely) throws Exception
    {
        final String extra = reliableAndTimely ? "X-DIRECT-FINAL-DESTINATION-DELIVERY:\r\n" : "";

        final String raw = "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "From: " + NOTIFIER + "\r\n"
                + "To: " + XD_RECIPIENT + "\r\n"
                + "Subject: MDN Notification\r\n"
                + "Message-ID: <notification-mdn-id@notifierdomain.example.org>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/report; report-type=disposition-notification; boundary=\"XBOUNDARY\"\r\n"
                + "\r\n"
                + "--XBOUNDARY\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "This is an MDN.\r\n"
                + "--XBOUNDARY\r\n"
                + "Content-Type: message/disposition-notification\r\n"
                + "\r\n"
                + "Disposition: automatic-action/MDN-sent-automatically;processed\r\n"
                + "Final-Recipient: rfc822;bob@example.com\r\n"
                + "Original-Message-ID: <parent-msg-id@senderdomain.example.org>\r\n"
                + extra
                + "\r\n"
                + "--XBOUNDARY--\r\n";

        return new MimeMessage(null, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static MimeMessage buildDsnMessage() throws Exception
    {
        final String raw = "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "From: " + NOTIFIER + "\r\n"
                + "To: " + XD_RECIPIENT + "\r\n"
                + "Subject: DSN Notification\r\n"
                + "Message-ID: <notification-dsn-id@notifierdomain.example.org>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/report; report-type=delivery-status; boundary=\"XBOUNDARY\"\r\n"
                + "\r\n"
                + "--XBOUNDARY\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n"
                + "\r\n"
                + "Delivery failed.\r\n"
                + "--XBOUNDARY\r\n"
                + "Content-Type: message/delivery-status\r\n"
                + "\r\n"
                + "Reporting-MTA: dns;notifierdomain.example.org\r\n"
                + "X-Original-Message-Id: <parent-msg-id@senderdomain.example.org>\r\n"
                + "\r\n"
                + "Final-Recipient: rfc822;bob@example.com\r\n"
                + "Action: failed\r\n"
                + "Status: 5.1.1\r\n"
                + "\r\n"
                + "--XBOUNDARY--\r\n";

        return new MimeMessage(null, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static MimeMessage buildImfMessage() throws Exception
    {
        final String raw = "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "From: " + SENDER + "\r\n"
                + "To: " + XD_RECIPIENT + "\r\n"
                + "Subject: Normal Message\r\n"
                + "Message-ID: <imf-message-id@senderdomain.example.org>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "Hello world.\r\n";

        return new MimeMessage(null, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
