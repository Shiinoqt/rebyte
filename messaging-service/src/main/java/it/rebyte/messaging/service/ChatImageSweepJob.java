package it.rebyte.messaging.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import it.rebyte.messaging.repository.MessageRepository;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

@Component
public class ChatImageSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ChatImageSweepJob.class);
    private final S3Client s3;
    private final MessageRepository messages;
    private final String bucket;
    private final String prefix;
    private final Duration grace;

    public ChatImageSweepJob(S3Client s3, MessageRepository messages,
            @Value("${rebyte.media.bucket}") String bucket,
            @Value("${rebyte.media.chat-prefix}") String prefix,
            @Value("${rebyte.media.chat-orphan-grace}") Duration grace) {
        this.s3 = s3;
        this.messages = messages;
        this.bucket = bucket;
        this.prefix = prefix;
        this.grace = grace;
    }

    public int sweep(Instant now) {
        Instant cutoff = now.minus(grace);
        int scanned = 0;
        int deleted = 0;
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).continuationToken(token).build());
            for (S3Object object : page.contents()) {
                scanned++;
                if (object.lastModified().isBefore(cutoff) && !messages.existsByImageKeys(object.key())) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
                    deleted++;
                }
            }
            token = page.nextContinuationToken();
        } while (token != null);
        log.info("Chat image sweep scanned {}, deleted {}", scanned, deleted);
        return deleted;
    }

    @Scheduled(cron = "${rebyte.media.chat-sweep-cron}")
    public void sweepOrphans() {
        try {
            sweep(Instant.now());
        } catch (Exception failure) {
            log.error("Chat image sweep failed: {}", failure.getClass().getSimpleName());
        }
    }
}
