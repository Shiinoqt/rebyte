package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.service.ChatImageService;
import it.rebyte.messaging.service.EmailPreferenceService;
import it.rebyte.messaging.email.EmailComposer;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.email.UnsubscribeTokens;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.TestJwtSupport;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "MEDIA_CHAT_IMAGE_MAX_SIZE_BYTES=524288",
        "MESSAGING_WS_ALLOWED_ORIGINS=https://rebyte.tech",
        "MESSAGING_WEB_BASE_URL=https://rebyte.tech",
        "MESSAGING_PUBLIC_API_URL=https://rebyte.tech",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.media.chat-sweep-cron=0 0 0 1 1 *" })
@ActiveProfiles("local-auth")
@AutoConfigureTestRestTemplate
@Import(TestJwtSupport.class)
class DemoMessagingConfigurationTest extends MessagingTestBase {

    @Autowired ChatImageService images;
    @Autowired ObjectMapper mapper;
    @Autowired EmailComposer composer;
    @Autowired UnsubscribeTokens tokens;
    @Autowired EmailPreferenceService preferences;
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;
    @MockitoBean AwsCredentialsProvider credentials;

    @BeforeEach
    void credentials() {
        when(credentials.resolveCredentials()).thenReturn(AwsBasicCredentials.create("demo-access", "demo-secret"));
    }

    @Test
    void demoEnvironmentReachesTheSignedChatPolicy() throws Exception {
        var response = images.createUploadPolicy("buyer-demo", "image/png");
        JsonNode policy = mapper.readTree(Base64.getDecoder().decode(response.policy()));
        JsonNode range = null;
        for (JsonNode condition : policy.get("conditions")) {
            if (condition.isArray() && "content-length-range".equals(condition.get(0).asText())) range = condition;
        }
        assertThat(range).isNotNull();
        assertThat(range.get(1).asLong()).isEqualTo(1);
        assertThat(range.get(2).asLong()).isEqualTo(524288L);
    }

    @Test
    void renderedLinksUseTheFrontendAndPreserveSignedUnsubscribeBehavior() {
        String sub = "email-demo-buyer";
        var email = composer.compose(EmailJob.of(EmailKind.NEW_MESSAGE, sub, "demo", Map.of(
                "senderUsername", "seller", "listingTitle", "Utility", "conversationId", "chat-demo")));
        assertThat(email.html()).contains("https://rebyte.tech/conversations/chat-demo").doesNotContain("localhost");
        assertThat(email.text()).contains("https://rebyte.tech/conversations/chat-demo").doesNotContain("localhost");
        assertThat(email.unsubscribeUrl()).startsWith(
                "https://rebyte.tech/notifications/email-preferences/unsubscribe?token=");
        URI unsubscribe = URI.create(email.unsubscribeUrl());
        String token = unsubscribe.getRawQuery().substring("token=".length());
        assertThat(tokens.verify(token)).isEqualTo(sub);
        String path = unsubscribe.getRawPath() + "?" + unsubscribe.getRawQuery();
        var preview = http.getForEntity(path, String.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody()).contains("<form method=\"post\"");
        assertThat(preferences.isEnabled(sub)).isTrue();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var response = http.postForEntity(path, new HttpEntity<>("List-Unsubscribe=One-Click", headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("You will no longer receive", "Order emails are still sent.");
        assertThat(preferences.isEnabled(sub)).isFalse();
        assertThat(preferences.isEnabled("email-demo-other")).isTrue();
    }

    @Test
    void configuredUnsubscribeHandlerRejectsAlteredSignatures() {
        String sub = "email-demo-tamper";
        String[] parts = tokens.issue(sub).split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        var response = http.postForEntity("/notifications/email-preferences/unsubscribe?token="
                + String.join(".", parts), null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("invalid-unsubscribe-token");
        assertThat(preferences.isEnabled(sub)).isTrue();
    }

    @Test
    void configuredDemoOriginCanUpgradeTheRealWebSocketEndpoint() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .header("Origin", "https://rebyte.tech")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() { })
                    .get(10, TimeUnit.SECONDS);
            assertThat(socket.isInputClosed()).isFalse();
            socket.abort();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://hostile.example", "https://rebyte.tech.hostile.example", "http://localhost:4200" })
    void configuredWebSocketOriginRejectsOtherSites(String origin) {
        try (var client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .header("Origin", origin)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() { })
                    .get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(failure -> {
                        assertThat(failure.getCause()).isInstanceOf(WebSocketHandshakeException.class);
                        assertThat(((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode())
                                .isEqualTo(403);
                    });
        }
    }
}
