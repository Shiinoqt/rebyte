package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.controller.OrderDeadLetterController;

/** A replay that fails after the DLQ delivery was pulled must hand the message back to the queue. */
class OrderDeadLetterControllerTest {
    private static final long DELIVERY_TAG = 7L;

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private OrderDeadLetterController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new OrderDeadLetterController(rabbit);
        doAnswer(invocation -> invocation.<ChannelCallback<?>>getArgument(0).doInRabbit(channel))
                .when(rabbit).execute(any());
        Map<String, Object> death = Map.of("exchange", RebyteExchanges.MARKETPLACE_EVENTS,
                "routing-keys", List.of(RebyteExchanges.LISTING_UPDATED));
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(Map.of("x-death", List.of(death))).build();
        when(channel.basicGet(RabbitConfig.LISTING_LIFECYCLE_DLQ, false)).thenReturn(
                new GetResponse(new Envelope(DELIVERY_TAG, false, "", RabbitConfig.LISTING_LIFECYCLE_DLQ),
                        props, new byte[] {1}, 0));
    }

    @Test
    void confirmTimeoutRequeuesTheDeadLetterInsteadOfLeavingItUnacked() throws Exception {
        doThrow(new TimeoutException("no confirm")).when(channel).waitForConfirmsOrDie(anyLong());

        assertThatThrownBy(() -> controller.replayOne(admin(), RabbitConfig.LISTING_LIFECYCLE_DLQ))
                .isInstanceOf(TimeoutException.class);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void brokerNackRequeuesTheDeadLetter() throws Exception {
        doThrow(new IOException("Nacks received")).when(channel).waitForConfirmsOrDie(anyLong());

        assertThatThrownBy(() -> controller.replayOne(admin(), RabbitConfig.LISTING_LIFECYCLE_DLQ))
                .isInstanceOf(IOException.class);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void confirmedReplayAcksTheDeadLetterWithoutNack() throws Exception {
        controller.replayOne(admin(), RabbitConfig.LISTING_LIFECYCLE_DLQ);

        verify(channel).basicPublish(eq(RebyteExchanges.MARKETPLACE_EVENTS), eq(RebyteExchanges.LISTING_UPDATED),
                any(AMQP.BasicProperties.class), any(byte[].class));
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    private Jwt admin() {
        return Jwt.withTokenValue("admin").header("alg", "none").subject("admin")
                .claim("cognito:groups", List.of("ADMIN")).build();
    }
}
