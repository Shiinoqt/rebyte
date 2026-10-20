package it.rebyte.messaging.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class EmailComposer {

    private final UnsubscribeTokens tokens;
    private final String webBaseUrl;
    private final String publicApiUrl;

    public EmailComposer(UnsubscribeTokens tokens, @Value("${rebyte.email.web-base-url}") String webBaseUrl,
            @Value("${rebyte.email.public-api-url}") String publicApiUrl) {
        this.tokens = tokens;
        this.webBaseUrl = webBaseUrl;
        this.publicApiUrl = publicApiUrl;
    }

    public ComposedEmail compose(EmailJob job) {
        String title = job.data().get("listingTitle");
        if (title == null || title.isBlank()) {
            title = "your item";
        }
        String summary;
        String link;
        String extra = "";
        switch (job.kind()) {
            case NEW_MESSAGE -> {
                String sender = job.data().get("senderUsername");
                if (sender == null || sender.isBlank()) {
                    sender = "a Rebyte user";
                }
                summary = "New message from " + sender + " about " + title;
                link = deepLink("conversations", job.data().get("conversationId"));
            }
            case ORDER_PAID -> {
                summary = "Payment confirmed for " + title;
                link = deepLink("orders", job.data().get("orderId"));
                if ("SOFTWARE".equals(job.data().get("listingType"))) {
                    extra = "Your licence is ready to download.";
                }
            }
            case NEW_VERSION -> {
                String version = job.data().get("versionNumber");
                summary = version == null || version.isBlank() ? title + " has a new version available"
                        : title + " " + version + " is now available";
                link = deepLink("listings", job.data().get("listingId"));
            }
            default -> throw new IllegalArgumentException("Unsupported email kind");
        }
        String html = "<!doctype html><html lang=\"en\"><body><p>" + HtmlUtils.htmlEscape(summary) + "</p>"
                + (extra.isEmpty() ? "" : "<p>" + HtmlUtils.htmlEscape(extra) + "</p>")
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">View on Rebyte</a></p>";
        String text = summary + "\n" + (extra.isEmpty() ? "" : extra + "\n") + link;
        String unsubscribeUrl = null;
        if (!job.kind().isTransactional()) {
            unsubscribeUrl = UriComponentsBuilder.fromUriString(publicApiUrl)
                    .pathSegment("notifications", "email-preferences", "unsubscribe")
                    .queryParam("token", "{token}").encode().buildAndExpand(tokens.issue(job.recipientSub()))
                    .toUriString();
            html += "<p><a href=\"" + HtmlUtils.htmlEscape(unsubscribeUrl)
                    + "\">Unsubscribe from message and release emails</a></p>";
            text += "\nUnsubscribe from message and release emails: " + unsubscribeUrl;
        }
        return new ComposedEmail(cleanSubject(summary), html + "</body></html>", text, unsubscribeUrl);
    }

    private String deepLink(String resource, String id) {
        return UriComponentsBuilder.fromUriString(webBaseUrl).pathSegment(resource, "{id}")
                .encode().buildAndExpand(id).toUriString();
    }

    private static String cleanSubject(String subject) {
        String cleaned = subject.replaceAll("[\\x00-\\x1F\\x7F]", "").replaceAll("(?U)\\s+", " ").strip();
        int length = cleaned.codePointCount(0, cleaned.length());
        return length > 120 ? cleaned.substring(0, cleaned.offsetByCodePoints(0, 120)) : cleaned;
    }
}
