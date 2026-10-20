package it.rebyte.catalog.messaging;

/** Identifiers and listing title for messaging's software release notification. */
public record VersionReleasedEvent(String listingId, String sellerSub, String versionNumber, String listingTitle) {
}
