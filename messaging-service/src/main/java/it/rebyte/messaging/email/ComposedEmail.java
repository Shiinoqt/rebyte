package it.rebyte.messaging.email;

public record ComposedEmail(String subject, String html, String text, String unsubscribeUrl) {
}
