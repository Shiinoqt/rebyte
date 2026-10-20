package it.rebyte.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.TaskInfo;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.commons.messaging.RebyteExchanges;

@Testcontainers
class ListingIndexerDeliveryIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void failedProjectionIsRedeliveredAndAcknowledgedAfterRecovery() throws Exception {
        Index index = mock(Index.class);
        when(index.getRawDocument("retry")).thenThrow(new IllegalStateException("search unavailable"))
                .thenReturn("{\"seq\":2}");
        ListingIndexer indexer = indexer(index);
        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            String queue = channel.queueDeclare().getQueue();
            publish(channel, queue, "retry");
            indexer.onListingChanged(List.of(receive(channel, queue)), channel);

            GetResponse retry = channel.basicGet(queue, false);
            assertThat(retry).isNotNull();
            assertThat(retry.getEnvelope().isRedeliver()).isTrue();
            indexer.onListingChanged(List.of(message(retry)), channel);

            assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero();
            assertThat(channel.basicGet(queue, false)).isNull();
        }
    }

    @Test
    void closingConsumerChannelReturnsUnacknowledgedWorkToFreshIndexer() throws Exception {
        Index index = mock(Index.class);
        when(index.getRawDocument("restart")).thenReturn("{\"seq\":0}");
        when(index.deleteDocument(anyString())).thenReturn(new TaskInfo());
        try (Connection connection = connection(); Channel producer = connection.createChannel()) {
            String queue = producer.queueDeclare().getQueue();
            publish(producer, queue, "restart");
            try (Channel consumer = connection.createChannel()) {
                assertThat(consumer.basicGet(queue, false)).isNotNull();
            }
            try (Channel consumer = connection.createChannel()) {
                GetResponse redelivered = consumer.basicGet(queue, false);
                assertThat(redelivered).isNotNull();
                assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();
                indexer(index).onListingChanged(List.of(message(redelivered)), consumer);
                assertThat(consumer.queueDeclarePassive(queue).getMessageCount()).isZero();
                verify(index).deleteDocument("restart");
            }
        }
    }

    private ListingIndexer indexer(Index index) {
        return new ListingIndexer(index, mapper, mock(ListingRepository.class), mock(ListingDocumentAssembler.class));
    }

    private Connection connection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());
        return factory.newConnection();
    }

    private void publish(Channel channel, String queue, String id) throws Exception {
        byte[] payload = mapper.writeValueAsString(new ListingChangedEvent(id, 1, Instant.now()))
                .getBytes(StandardCharsets.UTF_8);
        channel.basicPublish("", queue, null, payload);
        channel.queueDeclarePassive(queue);
    }

    private Message receive(Channel channel, String queue) throws Exception {
        GetResponse response = channel.basicGet(queue, false);
        assertThat(response).isNotNull();
        return message(response);
    }

    private Message message(GetResponse response) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
        properties.setReceivedRoutingKey(RebyteExchanges.LISTING_REMOVED);
        return new Message(response.getBody(), properties);
    }
}
