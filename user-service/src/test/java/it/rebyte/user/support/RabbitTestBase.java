package it.rebyte.user.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Extends {@link MySqlTestBase} with a RabbitMQ container (D-23): an
 * {@code *IT} that needs both the real MySQL claim/lock boundary and a real
 * broker gets both from one base class, matching the shared-container
 * idiom {@code MySqlTestBase} already established. Later services copy
 * this harness, so it stays minimal and self-explanatory.
 *
 * <p><b>Created here in plan 01-04 Task 2, not Task 3</b> where the plan's
 * own {@code files_modified} list originally placed it. Task 2's own
 * {@code OutboxRelayResilienceIT} needs real publisher confirms (D-18) too
 * — not just Task 3's exactly-once test — and Testcontainers' own
 * throwaway broker credentials (auto-wired via the container's own {@code
 * @ServiceConnection}) avoid ever needing
 * the real {@code docker-compose} broker's {@code RABBITMQ_PASSWORD},
 * which is a protected secret this executor cannot read. Task 3 reuses
 * this base as originally planned.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)} is inherited from
 * {@code MySqlTestBase} and covers this class too: the RabbitMQ container
 * below is still started and stopped per class, so a later class must not
 * reuse a cached context pointed at a stopped broker.
 */
public abstract class RabbitTestBase extends MySqlTestBase {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
}
