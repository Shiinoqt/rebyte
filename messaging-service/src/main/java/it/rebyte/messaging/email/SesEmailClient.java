package it.rebyte.messaging.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Component
public class SesEmailClient {
    private final SesV2Client client;
    private final String fromAddress;
    private final String configurationSet;

    public SesEmailClient(SesV2Client client, @Value("${rebyte.ses.from-address}") String fromAddress,
            @Value("${rebyte.ses.configuration-set}") String configurationSet) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("SES_FROM_ADDRESS must be set");
        }
        this.client = client;
        this.fromAddress = fromAddress;
        this.configurationSet = configurationSet;
    }

    public String send(String to, ComposedEmail email) {
        Message.Builder message = Message.builder().subject(content(email.subject()))
                .body(Body.builder().html(content(email.html())).text(content(email.text())).build());
        if (email.unsubscribeUrl() != null) {
            message.headers(MessageHeader.builder().name("List-Unsubscribe")
                            .value("<" + email.unsubscribeUrl() + ">").build(),
                    MessageHeader.builder().name("List-Unsubscribe-Post").value("List-Unsubscribe=One-Click").build());
        }
        return client.sendEmail(SendEmailRequest.builder().fromEmailAddress(fromAddress)
                .configurationSetName(configurationSet).destination(Destination.builder().toAddresses(to).build())
                .content(EmailContent.builder().simple(message.build()).build()).build()).messageId();
    }

    private static Content content(String value) {
        return Content.builder().charset("UTF-8").data(value).build();
    }
}
