package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.user.config.RabbitConfig;
import it.rebyte.user.messaging.ReviewCreatedEvent;
import it.rebyte.user.repository.SellerReviewRepository;
import it.rebyte.user.support.RabbitTestBase;

/**
 * The reputation projection listener over a real broker (D-15, D-47): a
 * synthetic {@code review.created} message published onto the real
 * exchange proves the binding, the routing key and the duplicate-ignoring
 * insert, ahead of order-service's real publisher (Phase 4).
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class ReviewCreatedListenerIT extends RabbitTestBase {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SellerReviewRepository sellerReviewRepository;

    @AfterEach
    void cleanUp() {
        sellerReviewRepository.deleteAll();
    }

    @Test
    void reviewCreatedInsertsOneRowAndSellerCountBecomesOne() {
        String sellerSub = UUID.randomUUID().toString();

        publish(new ReviewCreatedEvent(
                UUID.randomUUID().toString(), sellerSub, (byte) 5, Instant.now().truncatedTo(ChronoUnit.MICROS)));

        awaitCount(sellerSub, 1);
        assertThat(sellerReviewRepository.averageRatingBySellerSub(sellerSub)).isEqualTo(5.0);
    }

    @Test
    void redeliveringIdenticalEventLeavesCountAndAverageUnchanged() throws InterruptedException {
        String sellerSub = UUID.randomUUID().toString();
        ReviewCreatedEvent event = new ReviewCreatedEvent(
                UUID.randomUUID().toString(), sellerSub, (byte) 4, Instant.now().truncatedTo(ChronoUnit.MICROS));

        publish(event);
        awaitCount(sellerSub, 1);

        publish(event); // identical redelivery — D-15's at-least-once contract
        Thread.sleep(1000); // let the redelivered message reach the listener

        assertThat(sellerReviewRepository.countBySellerSub(sellerSub)).isEqualTo(1);
        assertThat(sellerReviewRepository.averageRatingBySellerSub(sellerSub)).isEqualTo(4.0);
    }

    @Test
    void twoDifferentReviewsForSameSellerYieldCountTwoAndAverageOfBoth() {
        String sellerSub = UUID.randomUUID().toString();

        publish(new ReviewCreatedEvent(
                UUID.randomUUID().toString(), sellerSub, (byte) 3, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        publish(new ReviewCreatedEvent(
                UUID.randomUUID().toString(), sellerSub, (byte) 5, Instant.now().truncatedTo(ChronoUnit.MICROS)));

        awaitCount(sellerSub, 2);
        assertThat(sellerReviewRepository.averageRatingBySellerSub(sellerSub)).isEqualTo(4.0);
    }

    @Test
    void rawOrderServiceJsonWithZeroRatingIsProjected() {
        String sellerSub = UUID.randomUUID().toString();
        String reviewId = UUID.randomUUID().toString();
        String json = "{\"reviewId\":\"" + reviewId + "\",\"sellerSub\":\"" + sellerSub
                + "\",\"rating\":0,\"occurredAt\":\"2026-09-29T10:15:30.123456Z\"}";

        rabbitTemplate.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.REVIEW_CREATED, json);

        awaitCount(sellerSub, 1);
        assertThat(sellerReviewRepository.averageRatingBySellerSub(sellerSub)).isEqualTo(0.0);
    }

    @Test
    void zeroAndFiveRatingsBothCountInAverage() {
        String sellerSub = UUID.randomUUID().toString();
        publishReview(sellerSub, (byte) 0);
        publishReview(sellerSub, (byte) 5);

        awaitCount(sellerSub, 2);
        assertThat(sellerReviewRepository.averageRatingBySellerSub(sellerSub)).isEqualTo(2.5);
    }

    @Test
    void averageRemainsUnrounded() {
        String sellerSub = UUID.randomUUID().toString();
        publishReview(sellerSub, (byte) 5);
        publishReview(sellerSub, (byte) 4);
        publishReview(sellerSub, (byte) 4);

        awaitCount(sellerSub, 3);
        assertThat(Math.abs(sellerReviewRepository.averageRatingBySellerSub(sellerSub) - (13.0 / 3.0)))
                .isLessThan(1e-9);
    }

    @Test
    void concurrentDistinctReviewsAreAllCounted() throws Exception {
        String sellerSub = UUID.randomUUID().toString();
        int threadCount = 4;
        int reviewsPerThread = 5;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService publishers = Executors.newFixedThreadPool(threadCount);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threadCount; thread++) {
                futures.add(publishers.submit(() -> {
                    barrier.await();
                    for (int review = 0; review < reviewsPerThread; review++) {
                        publishReview(sellerSub, (byte) (review % 6));
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            publishers.shutdownNow();
        }

        awaitCount(sellerSub, threadCount * reviewsPerThread);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void invalidPayloadIsDeadLetteredAndNotProjected(String scenario, String payload) {
        rabbitTemplate.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.REVIEW_CREATED, payload);

        awaitDeadLetter(payload);

        assertThat(sellerReviewRepository.count()).isZero();
    }

    @Test
    void sellerWithNoRowsReportsNullAverageAndZeroCount() {
        String sellerSub = UUID.randomUUID().toString();

        assertThat(sellerReviewRepository.countBySellerSub(sellerSub)).isZero();
        assertThat(sellerReviewRepository.averageRatingBySellerSub(sellerSub)).isNull();
    }

    private void publish(ReviewCreatedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.REVIEW_CREATED, json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishReview(String sellerSub, byte rating) {
        publish(new ReviewCreatedEvent(UUID.randomUUID().toString(), sellerSub, rating,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private void awaitDeadLetter(String expectedBody) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var dead = rabbitTemplate.receive(RabbitConfig.REVIEW_CREATED_DLQ);
            assertThat(dead).isNotNull();
            assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(expectedBody);
        });
    }

    static Stream<Arguments> invalidPayloads() {
        String id = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("unparseable body", "not-json"),
                Arguments.of("JSON null", "null"),
                Arguments.of("missing sellerSub", validWith("sellerSub", null)),
                Arguments.of("blank sellerSub", validWith("sellerSub", " ")),
                Arguments.of("rating 6", validWith("rating", 6)),
                Arguments.of("rating -1", validWith("rating", -1)),
                Arguments.of("missing rating", validWith("rating", null)),
                Arguments.of("reviewId of 65 characters", validWith("reviewId", "r".repeat(65))),
                Arguments.of("sellerSub of 65 characters", validWith("sellerSub", "s".repeat(65))),
                Arguments.of("non-ASCII reviewId", validWith("reviewId", id + "\u00e9")),
                Arguments.of("non-ASCII sellerSub", validWith("sellerSub", id + "\u00e9")),
                Arguments.of("missing occurredAt", validWith("occurredAt", null)));
    }

    private static String validWith(String field, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewId", UUID.randomUUID().toString());
        payload.put("sellerSub", UUID.randomUUID().toString());
        payload.put("rating", 3);
        payload.put("occurredAt", "2026-09-29T10:15:30.123456Z");
        if (value == null) {
            payload.remove(field);
        } else {
            payload.put(field, value);
        }
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitCount(String sellerSub, long expected) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sellerReviewRepository.countBySellerSub(sellerSub)).isEqualTo(expected));
    }
}
