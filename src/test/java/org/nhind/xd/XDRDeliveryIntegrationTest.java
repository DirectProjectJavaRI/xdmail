package org.nhind.xd;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.nhind.mail.service.DocumentRepository;
import org.nhind.mail.service.XDDeliveryCallback;
import org.nhind.mail.service.XDDeliveryCore;
import org.nhindirect.common.mail.SMTPMailMessage;
import org.nhindirect.common.tx.impl.DefaultTxDetailParser;
import org.nhindirect.common.tx.model.Tx;
import org.nhindirect.gateway.smtp.NotificationProducer;
import org.nhindirect.gateway.smtp.NotificationSettings;
import org.nhindirect.stagent.NHINDAddressCollection;
import org.nhindirect.stagent.mail.notifications.NotificationMessage;
import org.nhindirect.xd.common.SyntheticMetadataDefaults;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.transform.impl.DefaultMimeXdsTransformer;

@Disabled
public class XDRDeliveryIntegrationTest {

    private static final String XDR_ENDPOINT =
            "https://accreditation-testing.directtrust.org/xdr/non-authenticated/xdrpr";
    private static final String TEST_RECIPIENT = "test@accreditation-testing.directtrust.org";
    private static final String TEST_SENDER = "sender@test.directtrust.org";

    @Test
    public void testProcessAndDeliverXDMessageWithCCDA() throws Exception {

        RoutingResolver resolver = new RoutingResolver() {
            @Override public String resolve(String address) { return address; }
            @Override public boolean isXdEndpoint(String address) { return true; }
            @Override public boolean isSmtpEndpoint(String address) { return false; }
        };

        XDDeliveryCallback callback = new XDDeliveryCallback() {
            @Override public void sendNotificationMessage(NotificationMessage message) {}
            @Override public void sendFailureMessage(Tx tx, NHINDAddressCollection undeliveredRecipients,
                    boolean useSenderAsPostmaster) {}
        };

        NotificationProducer notificationProducer =
                new NotificationProducer(new NotificationSettings(false, "Test Agent", ""));

        XDDeliveryCore deliveryCore = new XDDeliveryCore(
                resolver,
                callback,
                new DefaultTxDetailParser(),
                new DefaultMimeXdsTransformer(new SyntheticMetadataDefaults()),
                new DocumentRepository(),
                notificationProducer,
                XDR_ENDPOINT);

        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.setFrom(new InternetAddress(TEST_SENDER));
        mimeMessage.setRecipients(MimeMessage.RecipientType.TO, InternetAddress.parse(TEST_RECIPIENT));
        mimeMessage.setSubject("XDR Integration Test - CCDA Ambulatory");

        MimeMultipart multipart = new MimeMultipart();

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("XDR integration test message with CCDA attachment.");
        multipart.addBodyPart(textPart);

        MimeBodyPart attachmentPart = new MimeBodyPart();
        try (InputStream ccdaStream = XDRDeliveryIntegrationTest.class
                .getResourceAsStream("/cdas/CCDA_Ambulatory.xml")) {
            String ccdaContent = new String(ccdaStream.readAllBytes(), StandardCharsets.UTF_8);
            attachmentPart.setContent(ccdaContent, "text/xml");
            attachmentPart.setFileName("CCDA_Ambulatory.xml");
        }
        multipart.addBodyPart(attachmentPart);

        mimeMessage.setContent(multipart);
        mimeMessage.saveChanges();

        // Serialize and re-parse so Jakarta Mail computes getSize() on all body parts.
        // DefaultMimeXdsTransformer skips any part where getSize() <= 0, which includes
        // -1 (unknown) returned for in-memory parts that have never been written out.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mimeMessage.writeTo(baos);
        MimeMessage parsedMessage = new MimeMessage(session, new ByteArrayInputStream(baos.toByteArray()));

        SMTPMailMessage smtpMessage = new SMTPMailMessage(
                parsedMessage,
                List.of(new InternetAddress(TEST_RECIPIENT)),
                new InternetAddress(TEST_SENDER));

        boolean result = deliveryCore.processAndDeliverXDMessage(smtpMessage);
        assertTrue(result, "XDR message delivery should succeed");
    }
}
