package it.rebyte.order.dto;

import java.util.List;

public record ReviewPage(List<ReviewView> items, String nextCursor) {
}
