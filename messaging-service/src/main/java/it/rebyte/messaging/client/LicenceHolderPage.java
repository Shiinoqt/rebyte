package it.rebyte.messaging.client;

import java.util.List;

public record LicenceHolderPage(List<String> buyerSubs, String nextCursor) {
}
