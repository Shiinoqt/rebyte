package it.rebyte.messaging.dto;

import java.util.List;

public record MessagePage(List<MessageView> items, String nextCursor) {
}
