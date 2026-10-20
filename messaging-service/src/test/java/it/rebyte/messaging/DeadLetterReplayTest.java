package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import it.rebyte.messaging.config.MessagingApiExceptionHandler;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.controller.MessagingDeadLetterController;
import it.rebyte.messaging.service.DeadLetterReplayService;

class DeadLetterReplayTest {

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger("it.rebyte.messaging");
    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    private MockMvc mvc;
    private final byte[] body = "private-message-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach
    void setup() {
        audit.start();
        logger.addAppender(audit);
        Jwt jwt = Jwt.withTokenValue("unused-test-token").header("alg", "none").subject("operator").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new MessagingDeadLetterController(new DeadLetterReplayService(rabbit)))
                .setControllerAdvice(new MessagingApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        when(rabbit.execute(any())).thenAnswer(invocation -> {
            try {
                return invocation.<ChannelCallback<?>>getArgument(0).doInRabbit(channel);
            } catch (Exception failure) {
                throw new AmqpException("Broker operation failed", failure);
            }
        });
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(audit);
        audit.stop();
    }

    @Test
    void replayConfirmsBeforeAckAndAuditsTheActorAndOriginalMessageId() throws Exception {
        AMQP.BasicProperties properties = delivery();

        var response = mvc.perform(post(path(RabbitConfig.EMAIL_SEND_DLQ))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String auditId = response.getHeader("X-Audit-Id");
        assertThat(UUID.fromString(auditId)).isNotNull();
        var ordered = inOrder(channel);
        ordered.verify(channel).basicPublish("", RabbitConfig.EMAIL_SEND_QUEUE, true, properties, body);
        ordered.verify(channel).waitForConfirmsOrDie(5000);
        ordered.verify(channel).basicAck(7, false);
        assertAudit("auditId=" + auditId, "adminSub=operator", "queue=messaging.email-send.dlq", "messageId=original-id");
    }

    @Test
    void emptyQueueReturnsNoContentWithoutPublishingOrAcknowledging() throws Exception {
        var response = mvc.perform(post(path(RabbitConfig.EMAIL_SEND_DLQ))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(204);
        verify(channel, never()).basicPublish(any(), any(), eq(true), any(), any());
        verify(channel, never()).basicAck(org.mockito.ArgumentMatchers.anyLong(), eq(false));
        assertAudit("adminSub=operator", "queue=messaging.email-send.dlq", "outcome=empty");
    }

    @Test
    void unknownQueueUsesTheDomainProblemWithoutBrokerIo() throws Exception {
        var response = mvc.perform(post(path("notify.unknown.dlq"))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"title\":\"dead-letter-queue-not-found\"")
                .contains("\"type\":\"urn:rebyte:problem:dead-letter-queue-not-found\"");
        verifyNoInteractions(rabbit, channel);
        assertAudit("adminSub=operator", "queue=notify.unknown.dlq", "outcome=unknown-queue");
    }

    @Test
    void confirmFailureRequeuesTheLetterAndAuditsTheFailedAttempt() throws Exception {
        delivery();
        doThrow(new IOException("unavailable")).when(channel).waitForConfirmsOrDie(5000);

        assertThatThrownBy(() -> mvc.perform(post(path(RabbitConfig.EMAIL_SEND_DLQ))))
                .hasRootCauseInstanceOf(IOException.class);

        verify(channel).basicNack(7, false, true);
        verify(channel, never()).basicAck(7, false);
        verify(channel).removeReturnListener(any(com.rabbitmq.client.ReturnListener.class));
        assertAudit("adminSub=operator", "queue=messaging.email-send.dlq", "messageId=original-id", "outcome=failed");
    }

    private AMQP.BasicProperties delivery() throws IOException {
        var properties = new AMQP.BasicProperties.Builder().messageId("original-id")
                .correlationId("original-correlation").contentType("application/json")
                .headers(Map.of("preserved", "value")).build();
        when(channel.basicGet(RabbitConfig.EMAIL_SEND_DLQ, false)).thenReturn(new GetResponse(
                new Envelope(7, false, "", RabbitConfig.EMAIL_SEND_DLQ), properties, body, 1));
        return properties;
    }

    private void assertAudit(String... fields) {
        List<String> lines = audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains(fields));
        assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain("private-message-body", "unused-test-token"));
        assertThat(audit.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private static String path(String queue) {
        return "/admin/messaging/dead-letters/" + queue + "/replay";
    }
}
