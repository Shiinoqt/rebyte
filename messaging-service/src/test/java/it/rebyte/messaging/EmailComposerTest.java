package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.rebyte.messaging.email.ComposedEmail;
import it.rebyte.messaging.email.EmailComposer;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.email.UnsubscribeTokens;

class EmailComposerTest {

    private final UnsubscribeTokens tokens = new UnsubscribeTokens("unit-test-unsubscribe-secret-0123456789", Duration.ofDays(365));
    private final EmailComposer composer = new EmailComposer(tokens, "https://web.example.test/", "https://api.example.test/");

    @Test
    void newMessageHasSummaryConversationLinkAndSignedUnsubscribe() {
        ComposedEmail email = compose(EmailKind.NEW_MESSAGE, Map.of("senderUsername", "alice", "conversationId", "chat-1"));
        assertThat(email.subject()).isEqualTo("New message from alice about Amiga");
        assertThat(email.html()).contains("https://web.example.test/conversations/chat-1");
        assertThat(email.text()).contains("https://web.example.test/conversations/chat-1");
        assertUnsubscribe(email);
    }

    @Test
    void missingOrBlankSenderHasGenericSummary() {
        assertThat(compose(EmailKind.NEW_MESSAGE, Map.of("conversationId", "chat-1")).subject())
                .isEqualTo("New message from a Rebyte user about Amiga");
        assertThat(compose(EmailKind.NEW_MESSAGE, Map.of("conversationId", "chat-1", "senderUsername", " ")).subject())
                .isEqualTo("New message from a Rebyte user about Amiga");
    }

    @Test
    void paidOrderIsTransactionalAndSoftwareMentionsLicence() {
        ComposedEmail email = compose(EmailKind.ORDER_PAID, Map.of("orderId", "order-1", "listingType", "SOFTWARE"));
        assertThat(email.subject()).isEqualTo("Payment confirmed for Amiga");
        assertThat(email.html()).contains("https://web.example.test/orders/order-1", "Your licence is ready to download.")
                .doesNotContain("Unsubscribe");
        assertThat(email.text()).contains("https://web.example.test/orders/order-1", "Your licence is ready to download.")
                .doesNotContain("Unsubscribe");
        assertThat(email.unsubscribeUrl()).isNull();
        assertThat(compose(EmailKind.ORDER_PAID, Map.of("orderId", "order-2", "listingType", "HARDWARE")).text())
                .doesNotContain("licence");
    }

    @Test
    void newVersionHasSummaryListingLinkAndSignedUnsubscribe() {
        ComposedEmail email = compose(EmailKind.NEW_VERSION, Map.of("listingId", "listing-1", "versionNumber", "2.0"));
        assertThat(email.subject()).isEqualTo("Amiga 2.0 is now available");
        assertThat(email.html()).contains("https://web.example.test/listings/listing-1");
        assertThat(email.text()).contains("https://web.example.test/listings/listing-1");
        assertUnsubscribe(email);
    }

    @Test
    void interpolatedHtmlIsEscapedWhileTextAndSubjectRemainUtf8() {
        ComposedEmail email = compose(EmailKind.NEW_MESSAGE, Map.of("listingTitle", "Città <b>&",
                "senderUsername", "<script>alice</script>", "conversationId", "chat-1"));
        assertThat(email.subject()).contains("Città <b>&", "<script>alice</script>");
        assertThat(email.text()).contains("Città <b>&", "<script>alice</script>");
        assertThat(email.html()).contains("&lt;b&gt;&amp;", "&lt;script&gt;alice&lt;/script&gt;")
                .doesNotContain("<b>", "<script>");
        ComposedEmail release = compose(EmailKind.NEW_VERSION,
                Map.of("listingId", "one", "versionNumber", "<img src=x>"));
        assertThat(release.html()).contains("&lt;img src=x&gt;").doesNotContain("<img");
    }

    @Test
    void pathVariablesAreEncodedAsOpaqueUtf8Segments() {
        ComposedEmail email = compose(EmailKind.NEW_MESSAGE, Map.of("conversationId", "città /?&\""));
        assertThat(email.text()).contains("/conversations/citt%C3%A0%20%2F%3F%26%22");
        assertThat(email.html()).contains("/conversations/citt%C3%A0%20%2F%3F%26%22");
    }

    @Test
    void subjectStripsHeaderControlsAndCollapsesWhitespace() {
        ComposedEmail email = compose(EmailKind.ORDER_PAID,
                Map.of("orderId", "one", "listingTitle", "Amiga\r\n\u0007\u007f  500"));
        assertThat(email.subject()).isEqualTo("Payment confirmed for Amiga 500");
    }

    @Test
    void subjectCapCountsUnicodeCodePoints() {
        ComposedEmail email = compose(EmailKind.ORDER_PAID, Map.of("orderId", "one", "listingTitle", "😀".repeat(300)));
        assertThat(email.subject().codePointCount(0, email.subject().length())).isEqualTo(120);
        assertThat(email.subject()).endsWith("😀");
        assertThat(compose(EmailKind.ORDER_PAID, Map.of("orderId", "one", "listingTitle", "a".repeat(300)))
                .subject().length()).isEqualTo(120);
    }

    @Test
    void messageTextIsNeverReadEvenWhenPresentInJob() {
        for (EmailKind kind : EmailKind.values()) {
            ComposedEmail email = compose(kind, Map.of("text", "private-message-content", "conversationId", "chat-1",
                    "orderId", "order-1", "listingId", "listing-1", "versionNumber", "1.0"));
            assertThat(email.subject()).doesNotContain("private-message-content");
            assertThat(email.html()).doesNotContain("private-message-content");
            assertThat(email.text()).doesNotContain("private-message-content");
        }
    }

    @Test
    void missingOrBlankListingTitleUsesNeutralLabel() {
        for (String title : new String[] {null, "", " "}) {
            Map<String, String> data = new HashMap<>();
            data.put("listingTitle", title);
            data.put("orderId", "order-1");
            ComposedEmail email = composer.compose(EmailJob.of(EmailKind.ORDER_PAID, "bob", "test", data));
            assertThat(email.subject()).isEqualTo("Payment confirmed for your item");
            assertThat(email.html()).contains("your item").doesNotContain("null");
            assertThat(email.text()).contains("your item").doesNotContain("null");
        }
    }

    @Test
    void missingVersionNumberDoesNotRenderNull() {
        ComposedEmail email = compose(EmailKind.NEW_VERSION, Map.of("listingId", "listing-1"));
        assertThat(email.subject()).isEqualTo("Amiga has a new version available");
        assertThat(email.html()).doesNotContain("null");
        assertThat(email.text()).doesNotContain("null");
    }

    private ComposedEmail compose(EmailKind kind, Map<String, String> values) {
        Map<String, String> data = new HashMap<>(Map.of("listingTitle", "Amiga"));
        data.putAll(values);
        return composer.compose(EmailJob.of(kind, "bob", "test", data));
    }

    private void assertUnsubscribe(ComposedEmail email) {
        assertThat(email.unsubscribeUrl()).startsWith("https://api.example.test/notifications/email-preferences/unsubscribe?token=");
        String token = email.unsubscribeUrl().substring(email.unsubscribeUrl().indexOf("token=") + 6);
        assertThat(tokens.verify(token)).isEqualTo("bob");
        assertThat(email.html()).contains(email.unsubscribeUrl());
        assertThat(email.text()).contains(email.unsubscribeUrl());
    }
}
