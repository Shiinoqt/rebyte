package it.rebyte.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;

class RabbitConfigTest {

    @Test
    void batchFactoryHonorsBootStartupSettingAndUsesTheConfiguredConnection() {
        RabbitProperties properties = new RabbitProperties();
        properties.getListener().getSimple().setAutoStartup(false);
        ConnectionFactory connection = mock(ConnectionFactory.class);
        var factory = new RabbitConfig().listingIndexerContainerFactory(connection,
                new SimpleRabbitListenerContainerFactoryConfigurer(properties));
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("indexer-test");
        endpoint.setQueueNames(RabbitConfig.LISTING_CHANGED_QUEUE);
        endpoint.setMessageListener(message -> { });

        var container = factory.createListenerContainer(endpoint);

        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.getConnectionFactory()).isSameAs(connection);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(container.isConsumerBatchEnabled()).isTrue();
        verifyNoInteractions(connection);
    }
}
