package it.rebyte.catalog.client;

public record UserSummary(String sub, String username, String avatar, boolean isDeveloper,
        Double ratingAvg, long ratingCount) {
}
