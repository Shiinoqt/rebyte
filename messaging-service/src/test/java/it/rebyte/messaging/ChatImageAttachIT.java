package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessagePage;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = {
        "MEDIA_CHAT_IMAGE_MAX_SIZE_BYTES=524288",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class ChatImageAttachIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final long MIB = 1024 * 1024;

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", CATALOG::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
    }

    @AfterAll
    static void stopServers() {
        CATALOG.close();
        USERS.close();
    }

    @Autowired TestRestTemplate rest;
    @Autowired MongoTemplate mongo;
    @MockitoBean S3Client s3;

    private final Map<String, HeadObjectResponse> objects = new HashMap<>();

    @BeforeEach
    void stubHeadObject() {
        objects.clear();
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            HeadObjectResponse response = objects.get(request.key());
            if (response == null) {
                throw NoSuchKeyException.builder().statusCode(404).message("missing").build();
            }
            return response;
        });
    }

    @Test
    void firstMessageWithTextAndImageReturnsAShortLivedPrivateUrl() {
        String buyer = sub("buyer");
        String key = upload(buyer, "image/jpeg", 200_000);

        ResponseEntity<ConversationView> started = start(buyer, sub("seller"), "Ecco la foto", List.of(key),
                ConversationView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MessageView first = latest(started.getBody().id(), buyer);
        assertThat(first.images()).singleElement().satisfies(image -> {
            assertThat(image.url()).contains(key).contains("X-Amz-Expires=300").doesNotContain("cloudfront");
            assertThat(image.expiresAt()).isAfter(Instant.now());
        });
    }

    @Test
    void firstMessageStillRequiresText() {
        String buyer = sub("buyer");
        String key = upload(buyer, "image/png", 1000);

        ResponseEntity<String> response = start(buyer, sub("seller"), null, List.of(key), String.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "invalid-message-text");
    }

    @Test
    void laterImageOnlySendNotifiesAndEnqueuesEmailAtomically() {
        Chat chat = chat();
        List<String> keys = List.of(upload(chat.seller(), "image/webp", 5000), upload(chat.seller(), "image/png", 7000));

        ResponseEntity<MessageView> sent = send(chat.seller(), chat.id(), null, keys, MessageView.class);

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sent.getBody().text()).isNull();
        assertThat(sent.getBody().images()).hasSize(2);
        assertThat(mongo.find(Query.query(Criteria.where("recipientSub").is(chat.buyer())
                .and("type").is(NotificationType.MESSAGE)
                .and("message.messageId").is(sent.getBody().id())), Notification.class)).hasSize(1);
        assertThat(mongo.find(Query.query(Criteria.where("routingKey").is(RabbitConfig.EMAIL_SEND_ROUTING_KEY)
                .and("payload").regex(sent.getBody().id())), OutboxEvent.class)).hasSize(1);
    }

    @Test
    void moreThanThreeImagesIsABadRequest() {
        Chat chat = chat();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            keys.add(upload(chat.buyer(), "image/jpeg", 100));
        }

        assertThat(send(chat.buyer(), chat.id(), "troppe", keys, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidAttachmentsAreRejected() {
        Chat chat = chat();
        String alice = chat.buyer();
        String ok = upload(alice, "image/jpeg", 100);
        String foreign = upload("bob-" + UUID.randomUUID(), "image/jpeg", 100);
        String tooBig = upload(alice, "image/jpeg", 2 * MIB);
        String gif = upload(alice, "image/gif", 100);
        String stale = key(alice);
        objects.put(stale, head("image/jpeg", 100, Instant.now().minusSeconds(25 * 3600)));

        for (List<String> keys : List.of(List.of(ok, ok), List.of(foreign), List.of(tooBig), List.of(gif),
                List.of(stale), List.of("chat/" + alice + "/not-a-uuid"))) {
            assertProblem(send(alice, chat.id(), "foto", keys, String.class), HttpStatus.UNPROCESSABLE_CONTENT,
                    "invalid-chat-image");
        }
        assertProblem(send(alice, chat.id(), "foto", List.of(key(alice)), String.class),
                HttpStatus.UNPROCESSABLE_CONTENT, "chat-image-not-uploaded");
    }

    @ParameterizedTest
    @CsvSource({ "0,false", "524288,true", "524289,false" })
    void configuredDemoSizeIsEnforcedBeforeMessageMutation(long size, boolean accepted) {
        Chat chat = chat();
        String key = upload(chat.buyer(), "image/png", size);
        long before = seq(chat.id());
        if (accepted) {
            ResponseEntity<MessageView> response = send(chat.buyer(), chat.id(), "image", List.of(key), MessageView.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().images()).hasSize(1);
            assertThat(seq(chat.id())).isEqualTo(before + 1);
        } else {
            assertProblem(send(chat.buyer(), chat.id(), "image", List.of(key), String.class),
                    HttpStatus.UNPROCESSABLE_CONTENT, "invalid-chat-image");
            assertThat(seq(chat.id())).isEqualTo(before);
        }
    }

    @Test
    void reusingAnAttachedKeyIsAConflict() {
        Chat chat = chat();
        String key = upload(chat.buyer(), "image/jpeg", 100);
        assertThat(send(chat.buyer(), chat.id(), "una", List.of(key), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        long seqBefore = seq(chat.id());

        assertProblem(send(chat.buyer(), chat.id(), "di nuovo", List.of(key), String.class), HttpStatus.CONFLICT,
                "chat-image-already-attached");
        assertThat(seq(chat.id())).isEqualTo(seqBefore);
    }

    @Test
    void outsiderIsRejectedBeforeAnyStorageAccess() {
        Chat chat = chat();
        String outsider = sub("outsider");
        String key = upload(outsider, "image/jpeg", 100);
        Mockito.clearInvocations(s3);

        assertProblem(send(outsider, chat.id(), "ciao", List.of(key), String.class), HttpStatus.NOT_FOUND,
                "conversation-not-found");
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void storedDocumentsHoldKeysNeverUrls() {
        Chat chat = chat();
        String key = upload(chat.seller(), "image/jpeg", 100);
        send(chat.seller(), chat.id(), null, List.of(key), MessageView.class);

        List<Document> messages = mongo.find(Query.query(Criteria.where("conversationId").is(chat.id())),
                Document.class, "message");
        Document conversation = mongo.findById(chat.id(), Document.class, "conversation");
        assertThat(messages.toString() + conversation.toJson()).doesNotContain("http").doesNotContain("X-Amz");
        assertThat(messages).anySatisfy(message -> assertThat(message.getList("imageKeys", String.class))
                .containsExactly(key));
    }

    private long seq(String conversationId) {
        return mongo.findById(conversationId, Document.class, "conversation").get("messageSeq", Number.class)
                .longValue();
    }

    private MessageView latest(String conversationId, String sub) {
        ResponseEntity<MessagePage> page = rest.exchange("/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers(sub)), MessagePage.class);
        return page.getBody().items().getFirst();
    }

    private String upload(String owner, String contentType, long size) {
        String key = key(owner);
        objects.put(key, head(contentType, size, Instant.now()));
        return key;
    }

    private static String key(String owner) {
        return "chat/" + owner + "/" + UUID.randomUUID();
    }

    private static HeadObjectResponse head(String contentType, long size, Instant lastModified) {
        return HeadObjectResponse.builder().contentType(contentType).contentLength(size).lastModified(lastModified)
                .build();
    }

    private Chat chat() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        ConversationView view = start(buyer, seller, "Disponibile?", null, ConversationView.class).getBody();
        return new Chat(view.id(), buyer, seller);
    }

    private <T> ResponseEntity<T> start(String buyer, String seller, String text, List<String> keys, Class<T> type) {
        String listingId = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + listingId, 200, "{\"id\":\"" + listingId + "\",\"sellerSub\":\""
                + seller + "\",\"listingTitle\":\"Oggetto\",\"price\":10.00,\"type\":\"HARDWARE\",\"available\":true}");
        Map<String, Object> body = new HashMap<>();
        body.put("listingId", listingId);
        body.put("text", text);
        body.put("imageKeys", keys);
        return rest.exchange("/conversations", HttpMethod.POST, new HttpEntity<>(body, headers(buyer)), type);
    }

    private <T> ResponseEntity<T> send(String sub, String conversationId, String text, List<String> keys,
            Class<T> type) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("imageKeys", keys);
        return rest.exchange("/conversations/" + conversationId + "/messages", HttpMethod.POST,
                new HttpEntity<>(body, headers(sub)), type);
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
    }

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"title\":\"" + code + "\"");
    }

    private static String sub(String role) {
        return role + "-" + UUID.randomUUID();
    }

    private record Chat(String id, String buyer, String seller) {
    }
}
