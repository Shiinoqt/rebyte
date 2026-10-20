package it.rebyte.order.dto;

import java.util.List;

public record LicenceHolderPage(List<String> buyerSubs, String nextCursor) {
}
