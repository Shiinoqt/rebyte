package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.domain.EmailSuppression;
import it.rebyte.messaging.email.SesEventProcessor;
import it.rebyte.messaging.email.SuppressionService;
import it.rebyte.messaging.support.MessagingTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
class SesEventIntakeIT extends MessagingTestBase {

    @Autowired SesEventProcessor processor;
    @Autowired SuppressionService suppressions;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), EmailSuppression.class);
    }

    @Test
    void permanentBounceIsStoredOncePerAddressAndRedeliveryOnlyBumpsTheCount() throws InterruptedException {
        processor.process(fixture("bounce-permanent"));
        EmailSuppression first = mongo.findById("bounced.one@example.test", EmailSuppression.class);
        assertThat(first).isNotNull();

        Thread.sleep(20);
        processor.process(fixture("bounce-permanent"));

        assertThat(mongo.findAll(EmailSuppression.class)).hasSize(2);
        EmailSuppression second = mongo.findById("bounced.one@example.test", EmailSuppression.class);
        assertThat(second.getCount()).isEqualTo(2);
        assertThat(second.getFirstSeenAt()).isEqualTo(first.getFirstSeenAt());
        assertThat(second.getLastSeenAt()).isAfter(first.getLastSeenAt());
        assertThat(second.getReason()).isEqualTo(EmailSuppression.BOUNCE_PERMANENT);
        assertThat(second.getSubType()).isEqualTo("General");
        assertThat(second.getLastSesMessageId()).isEqualTo("ses-message-bounce-permanent-0001");
        assertThat(mongo.findById("second.bounced@example.test", EmailSuppression.class)).isNotNull();
    }

    @Test
    void matchingIgnoresCaseAndSurroundingWhitespace() {
        processor.process(fixture("bounce-permanent"));

        assertThat(suppressions.isSuppressed("BOUNCED.ONE@EXAMPLE.TEST")).isTrue();
        assertThat(suppressions.isSuppressed(" second.bounced@example.test ")).isTrue();
        assertThat(suppressions.isSuppressed("someone.else@example.test")).isFalse();
    }

    @Test
    void complaintAddsARecordAndOtherEventsAddNone() {
        processor.process(fixture("bounce-permanent"));
        processor.process(fixture("complaint"));
        assertThat(mongo.findAll(EmailSuppression.class)).hasSize(3);

        processor.process(fixture("bounce-transient"));
        processor.process(fixture("reject"));
        processor.process(fixture("delivery"));

        assertThat(mongo.findAll(EmailSuppression.class)).hasSize(3);
        EmailSuppression complaint = mongo.findById("complainer@example.test", EmailSuppression.class);
        assertThat(complaint.getReason()).isEqualTo(EmailSuppression.COMPLAINT);
        assertThat(complaint.getSubType()).isEqualTo("abuse");
        assertThat(suppressions.isSuppressed("full.mailbox@example.test")).isFalse();
    }

    private static String fixture(String name) {
        try (InputStream in = SesEventIntakeIT.class.getResourceAsStream("/ses/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
