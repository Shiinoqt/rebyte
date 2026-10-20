package it.rebyte.order.dto;

import java.util.List;

public record OfferPage(List<OfferView> items, String nextCursor) {
}
