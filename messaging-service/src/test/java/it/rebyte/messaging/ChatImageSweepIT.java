package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.repository.MessageRepository;
import it.rebyte.messaging.service.ChatImageSweepJob;
import it.rebyte.messaging.support.MessagingTestBase;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.media.chat-sweep-cron=0 0 0 1 1 *"
})
@ActiveProfiles("local-auth")
class ChatImageSweepIT extends MessagingTestBase {

    @Autowired ChatImageSweepJob sweep;
    @Autowired MessageRepository messages;
    @MockitoBean S3Client s3;

    @BeforeEach
    void clean() {
        messages.deleteAll();
    }

    @Test
    void paginatedSweepDeletesOnlyUnreferencedImagesStrictlyOlderThanOneDay() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        String referenced = "chat/alice/" + UUID.randomUUID();
        String orphan = "chat/alice/" + UUID.randomUUID();
        String recent = "chat/bob/" + UUID.randomUUID();
        String boundary = "chat/bob/" + UUID.randomUUID();
        messages.save(new Message(UUID.randomUUID().toString(), "conversation-1", 1, "alice", "bob",
                MessageKind.CHAT, "Attached image", List.of(referenced), null, null,
                now.minus(Duration.ofDays(2)), false, null));
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("next-page")
                        .contents(object(referenced, now.minus(Duration.ofDays(2)))).build(),
                ListObjectsV2Response.builder().isTruncated(false)
                        .contents(object(orphan, now.minus(Duration.ofDays(2))),
                                object(recent, now.minus(Duration.ofHours(1))),
                                object(boundary, now.minus(Duration.ofDays(1))))
                        .build());
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        assertThat(sweep.sweep(now)).isEqualTo(1);

        var lists = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3, times(2)).listObjectsV2(lists.capture());
        assertThat(lists.getAllValues()).allSatisfy(request -> {
            assertThat(request.bucket()).isEqualTo("messaging-test-bucket");
            assertThat(request.prefix()).isEqualTo("chat/");
        });
        assertThat(lists.getAllValues().get(0).continuationToken()).isNull();
        assertThat(lists.getAllValues().get(1).continuationToken()).isEqualTo("next-page");
        var deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deletes.capture());
        assertThat(deletes.getValue().bucket()).isEqualTo("messaging-test-bucket");
        assertThat(deletes.getValue().key()).isEqualTo(orphan);
        assertThat(messages.findAll()).singleElement().satisfies(message ->
                assertThat(message.getImageKeys()).containsExactly(referenced));
    }

    private static S3Object object(String key, Instant lastModified) {
        return S3Object.builder().key(key).lastModified(lastModified).size(100L).build();
    }
}
