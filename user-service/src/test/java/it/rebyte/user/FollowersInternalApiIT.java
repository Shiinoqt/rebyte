package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.user.domain.Follow;
import it.rebyte.user.domain.FollowId;
import it.rebyte.user.repository.FollowRepository;
import it.rebyte.user.support.MySqlTestBase;

/**
 * The internal follower fan-out read (SOCL-05, D-40, D-46), unauthenticated
 * by design — no bearer token anywhere in this class. Proves the keyset
 * pagination contract: total ordering, no duplicate and no gap across a
 * multi-page walk, a follower exactly on a cursor boundary returned
 * exactly once, determinism across repeated identical requests, no
 * resurfacing of an earlier-page follower after a concurrent insert, and
 * the default/clamp behaviour of the page-size parameter (D-46).
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class FollowersInternalApiIT extends MySqlTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FollowRepository followRepository;

    @AfterEach
    void cleanUp() {
        followRepository.deleteAll();
    }

    @Test
    void userWithZeroFollowersReturns200EmptyListNullCursor() {
        String sub = UUID.randomUUID().toString();

        ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users/" + sub + "/followers", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("subs")).isEmpty();
        assertThat(response.getBody().get("nextCursor")).isNull();
    }

    @Test
    void walkingEveryPageReturnsEachFollowerExactlyOnce() {
        String followedSub = UUID.randomUUID().toString();
        List<String> followerSubs = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            String followerSub = String.format("follower-%04d-%s", i, UUID.randomUUID());
            followerSubs.add(followerSub);
            followRepository.save(new Follow(new FollowId(followerSub, followedSub), Instant.now()));
        }

        List<String> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> page = fetchPage(followedSub, cursor, 50);
            List<String> subs = (List<String>) page.get("subs");
            collected.addAll(subs);
            cursor = (String) page.get("nextCursor");
            pages++;
            assertThat(pages).isLessThan(20); // sanity guard against an infinite loop
        } while (cursor != null);

        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).containsExactlyInAnyOrderElementsOf(followerSubs);
        assertThat(collected).hasSize(250);
    }

    @Test
    void followerEqualToCursorAppearsOnExactlyOneOfTwoAdjacentPages() {
        String followedSub = UUID.randomUUID().toString();
        List<String> subs = List.of("a", "b", "c").stream()
                .map(prefix -> prefix + "-" + UUID.randomUUID())
                .sorted()
                .toList();
        for (String s : subs) {
            followRepository.save(new Follow(new FollowId(s, followedSub), Instant.now()));
        }
        String cursorValue = subs.get(0);

        Map<String, Object> firstPage = fetchPage(followedSub, null, 1);
        assertThat((List<String>) firstPage.get("subs")).containsExactly(cursorValue);
        assertThat(firstPage.get("nextCursor")).isEqualTo(cursorValue);

        Map<String, Object> secondPage = fetchPage(followedSub, cursorValue, 10);
        List<String> secondSubs = (List<String>) secondPage.get("subs");
        assertThat(secondSubs).doesNotContain(cursorValue);
        assertThat(secondSubs).containsExactly(subs.get(1), subs.get(2));
    }

    @Test
    void twoIdenticalCursorRequestsReturnIdenticalPages() {
        String followedSub = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            followRepository.save(
                    new Follow(new FollowId("f" + i + "-" + UUID.randomUUID(), followedSub), Instant.now()));
        }

        Map<String, Object> first = fetchPage(followedSub, null, 3);
        Map<String, Object> second = fetchPage(followedSub, null, 3);

        assertThat(first.get("subs")).isEqualTo(second.get("subs"));
        assertThat(first.get("nextCursor")).isEqualTo(second.get("nextCursor"));
    }

    @Test
    void insertingFollowBetweenPagesDoesNotResurfaceEarlierPageFollower() {
        String followedSub = UUID.randomUUID().toString();
        String early = "a-" + UUID.randomUUID();
        String late = "z-" + UUID.randomUUID();
        followRepository.save(new Follow(new FollowId(early, followedSub), Instant.now()));
        followRepository.save(new Follow(new FollowId(late, followedSub), Instant.now()));

        Map<String, Object> firstPage = fetchPage(followedSub, null, 1);
        assertThat((List<String>) firstPage.get("subs")).containsExactly(early);
        String cursor = (String) firstPage.get("nextCursor");

        // A follower sorting between the two existing ones is inserted AFTER
        // the first page was already served.
        String insertedMidway = "b-" + UUID.randomUUID();
        followRepository.save(new Follow(new FollowId(insertedMidway, followedSub), Instant.now()));

        Map<String, Object> secondPage = fetchPage(followedSub, cursor, 10);
        assertThat((List<String>) secondPage.get("subs")).doesNotContain(early);
    }

    @Test
    void omittingLimitYieldsAtMost100EntriesDefault() {
        String followedSub = UUID.randomUUID().toString();
        for (int i = 0; i < 150; i++) {
            followRepository.save(
                    new Follow(new FollowId(String.format("f%04d-%s", i, UUID.randomUUID()), followedSub), Instant.now()));
        }

        Map<String, Object> page = fetchPage(followedSub, null, null);

        assertThat((List<String>) page.get("subs")).hasSize(100);
        assertThat(page.get("nextCursor")).isNotNull();
    }

    @Test
    void requestingAboveCapClampsAndReturns200NotBadRequest() {
        String followedSub = UUID.randomUUID().toString();
        followRepository.save(new Follow(new FollowId(UUID.randomUUID().toString(), followedSub), Instant.now()));

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/internal/users/" + followedSub + "/followers?limit=5000", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) response.getBody().get("subs")).hasSizeLessThanOrEqualTo(500);
    }

    private Map<String, Object> fetchPage(String followedSub, String cursor, Integer limit) {
        StringBuilder url = new StringBuilder("/internal/users/").append(followedSub).append("/followers?");
        if (cursor != null) {
            url.append("cursor=").append(cursor).append('&');
        }
        if (limit != null) {
            url.append("limit=").append(limit);
        }
        ResponseEntity<Map> response = restTemplate.getForEntity(url.toString(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
