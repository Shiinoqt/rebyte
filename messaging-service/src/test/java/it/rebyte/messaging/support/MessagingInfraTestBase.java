package it.rebyte.messaging.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Adds a disposable RabbitMQ on top of {@link MessagingTestBase}. Its
 * {@code @ServiceConnection} replaces the unreachable Rabbit canary port.
 */
public abstract class MessagingInfraTestBase extends MessagingTestBase {

    @Container
    @ServiceConnection
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
}
