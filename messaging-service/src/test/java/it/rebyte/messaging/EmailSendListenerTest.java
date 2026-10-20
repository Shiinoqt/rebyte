package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.email.EmailSendService;
import it.rebyte.messaging.messaging.EmailSendListener;

class EmailSendListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EmailSendService service = mock(EmailSendService.class);
    private final EmailSendListener listener = new EmailSendListener(mapper, service);

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void requiredLinkIdMustBePresentAndNonBlank(EmailKind kind) throws Exception {
        String field = switch (kind) {
            case NEW_MESSAGE -> "conversationId";
            case ORDER_PAID -> "orderId";
            case NEW_VERSION -> "listingId";
        };
        for (String id : new String[] {null, "", " "}) {
            Map<String, String> data = new HashMap<>();
            data.put(field, id);
            EmailJob job = EmailJob.of(kind, "bob", "test", data);
            Message message = new Message(mapper.writeValueAsBytes(job), new MessageProperties());
            assertThatThrownBy(() -> listener.onEmail(message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void validLinkIdReachesService(EmailKind kind) throws Exception {
        EmailJob job = EmailJob.of(kind, "bob", "test", Map.of("conversationId", "chat", "orderId", "order",
                "listingId", "listing"));
        listener.onEmail(new Message(mapper.writeValueAsBytes(job), new MessageProperties()));
        verify(service).process(job);
    }
}
