package it.rebyte.order.dto;

import java.util.List;

public record OrderPage(List<OrderView> items, String nextCursor) {
}
