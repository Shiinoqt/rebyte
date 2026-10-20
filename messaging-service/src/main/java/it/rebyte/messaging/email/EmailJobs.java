package it.rebyte.messaging.email;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations.BulkMode;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.bulk.BulkWriteError;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.outbox.DomainEventRecorder;
import it.rebyte.messaging.outbox.OutboxEvent;

/**
 * Enqueues email jobs through the outbox so a job is published only after the
 * transaction that produced it commits.
 */
@Component
public class EmailJobs {

    private static final int DUPLICATE_KEY = 11000;

    private final DomainEventRecorder recorder;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public EmailJobs(DomainEventRecorder recorder, MongoTemplate mongoTemplate, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Joins the caller's transaction. A job whose id already exists fails with a
     * duplicate key error, which aborts that transaction.
     */
    public void enqueue(EmailJob job) {
        recorder.record(job.eventId(), RabbitConfig.EMAIL_SEND_ROUTING_KEY, job, job.occurredAt());
    }

    /**
     * Bulk-inserts jobs outside any transaction, because one duplicate would
     * abort every row of a transactional batch. Returns how many jobs were newly
     * enqueued; jobs that already exist count as enqueued earlier.
     */
    public int enqueueAllIgnoringDuplicates(List<EmailJob> jobs) {
        if (jobs.isEmpty()) {
            return 0;
        }
        List<OutboxEvent> rows = new ArrayList<>(jobs.size());
        for (EmailJob job : jobs) {
            rows.add(new OutboxEvent(job.eventId(), RabbitConfig.EMAIL_SEND_ROUTING_KEY, toJson(job), job.occurredAt()));
        }
        try {
            mongoTemplate.bulkOps(BulkMode.UNORDERED, OutboxEvent.class).insert(rows).execute();
            return rows.size();
        } catch (BulkOperationException failure) {
            Set<Integer> rejected = new HashSet<>();
            for (BulkWriteError error : failure.getErrors()) {
                if (error.getCode() != DUPLICATE_KEY) {
                    throw failure;
                }
                rejected.add(error.getIndex());
            }
            return rows.size() - rejected.size();
        }
    }

    private String toJson(EmailJob job) {
        try {
            return objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize email job " + job.eventId(), e);
        }
    }
}
