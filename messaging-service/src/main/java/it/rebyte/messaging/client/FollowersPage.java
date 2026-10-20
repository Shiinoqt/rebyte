package it.rebyte.messaging.client;

import java.util.List;

public record FollowersPage(List<String> subs, String nextCursor) {
}
