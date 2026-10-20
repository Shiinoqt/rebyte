package it.rebyte.messaging.dto;

import java.util.List;

public record ConversationPage(List<ConversationView> items, String nextCursor) {
}
