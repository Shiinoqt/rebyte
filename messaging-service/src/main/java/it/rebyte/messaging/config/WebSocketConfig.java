package it.rebyte.messaging.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import it.rebyte.messaging.realtime.SessionRegistry;
import it.rebyte.messaging.realtime.StompAuthInterceptor;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authentication;
    private final SessionRegistry sessions;
    private final String[] allowedOrigins;
    private final long heartbeatMs;
    private final int messageSizeLimit;
    private final int timeToFirstMessageMs;
    private TaskScheduler brokerScheduler;

    public WebSocketConfig(StompAuthInterceptor authentication, SessionRegistry sessions,
            @Value("${rebyte.realtime.allowed-origins}") String allowedOrigins,
            @Value("${rebyte.realtime.heartbeat-ms}") long heartbeatMs,
            @Value("${rebyte.realtime.message-size-limit}") int messageSizeLimit,
            @Value("${rebyte.realtime.time-to-first-message-ms}") int timeToFirstMessageMs) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
        this.heartbeatMs = heartbeatMs;
        this.messageSizeLimit = messageSizeLimit;
        this.timeToFirstMessageMs = timeToFirstMessageMs;
    }

    @Autowired
    public void setMessageBrokerTaskScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        brokerScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] {heartbeatMs, heartbeatMs})
                .setTaskScheduler(brokerScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authentication);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(messageSizeLimit).setTimeToFirstMessage(timeToFirstMessageMs)
                .addDecoratorFactory(sessions::decorate);
    }
}
