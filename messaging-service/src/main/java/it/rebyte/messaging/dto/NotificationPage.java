package it.rebyte.messaging.dto;

import java.util.List;

public record NotificationPage(List<NotificationView> items, String nextCursor) {
}
