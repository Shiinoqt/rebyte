package it.rebyte.messaging.messaging;

public record VersionReleasedEvent(String listingId, String sellerSub, String versionNumber, String listingTitle) {
}
