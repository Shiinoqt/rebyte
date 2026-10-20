package it.rebyte.gateway.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;

import it.rebyte.gateway.ApiGatewayApplication;
import it.rebyte.messaging.MessagingServiceApplication;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.realtime.SessionRegistry;
import it.rebyte.messaging.realtime.StompAuthInterceptor;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Mono;

public final class GatewayMessagingCluster implements AutoCloseable {

    // The gateway jar shares this classpath; its Redis rate limiter configuration ignores the webflux enabled
    // flag, so the messaging contexts exclude every gateway auto-configuration explicitly.
    private static final String GATEWAY_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayResilience4JCircuitBreakerAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayFunctionAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayStreamAutoConfiguration",
            "org.springframework.cloud.gateway.discovery.GatewayDiscoveryClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.SimpleUrlHandlerMappingGlobalCorsAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayReactiveLoadBalancerClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.LocalResponseCacheAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayTracingAutoConfiguration");

    public static final String INSTANCE_HEADER = "X-Messaging-Instance";
    private static final String SERVICE = "messaging-service";
    private final MongoDBContainer mongo = new MongoDBContainer("mongo:7");
    private final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management");
    private final Map<String, ConfigurableApplicationContext> messaging = new LinkedHashMap<>();
    private final List<String> instanceIds = List.of("gateway-it-messaging-1", "gateway-it-messaging-2");
    private StubHttpServer catalog;
    private StubHttpServer users;
    private ConfigurableApplicationContext gateway;
    private ModuleConfiguration messagingConfiguration;
    private ModuleConfiguration gatewayConfiguration;
    private long lastWebSocketAttempt;

    private GatewayMessagingCluster() {
    }

    public static GatewayMessagingCluster start() {
        GatewayMessagingCluster cluster = new GatewayMessagingCluster();
        try {
            cluster.startResourcesAndApplications();
            return cluster;
        } catch (RuntimeException | Error failure) {
            try {
                cluster.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void startResourcesAndApplications() {
        messagingConfiguration = ModuleConfiguration.forApplication(MessagingServiceApplication.class);
        gatewayConfiguration = ModuleConfiguration.forApplication(ApiGatewayApplication.class);
        mongo.start();
        redis.start();
        rabbit.start();
        catalog = new StubHttpServer();
        users = new StubHttpServer();
        for (String instanceId : instanceIds) {
            List<String> arguments = commonArguments(messagingConfiguration, WebApplicationType.SERVLET);
            arguments.addAll(List.of(
                    "--eureka.instance.instance-id=" + instanceId,
                    "--spring.autoconfigure.exclude=" + GATEWAY_AUTO_CONFIGURATIONS,
                    "--rebyte.media.bucket=gateway-messaging-test-bucket",
                    "--rebyte.media.region=eu-south-1",
                    "--rebyte.messaging.aws.access-key-id=AKIAMESSAGINGTEST000",
                    "--rebyte.messaging.aws.secret-access-key=messaging-test-secret-not-real",
                    "--rebyte.ses.from-address=no-reply@example.test",
                    "--rebyte.ses.events.enabled=false",
                    "--rebyte.email.unsubscribe.secret=messaging-test-unsubscribe-secret-0123456789",
                    "--spring.cloud.openfeign.client.config.catalog-service.url=" + catalog.baseUrl(),
                    "--spring.cloud.openfeign.client.config.user-service.url=" + users.baseUrl(),
                    "--spring.cloud.openfeign.client.config.order-service.url=" + users.baseUrl(),
                    "--rebyte.media.chat-sweep-cron=-"));
            ConfigurableApplicationContext context = new SpringApplicationBuilder(MessagingServiceApplication.class,
                    MessagingFixtureConfiguration.class).web(WebApplicationType.SERVLET)
                    .registerShutdownHook(false).run(arguments.toArray(String[]::new));
            messaging.put(instanceId, context);
        }
        List<String> arguments = commonArguments(gatewayConfiguration, WebApplicationType.REACTIVE);
        for (int index = 0; index < instanceIds.size(); index++) {
            arguments.add("--spring.cloud.discovery.client.simple.instances.messaging-service[" + index + "].uri="
                    + "http://127.0.0.1:" + port(messaging.get(instanceIds.get(index))));
        }
        // Simple discovery has no deregistration event; avoid caching its changed membership after shutdown.
        arguments.add("--spring.cloud.loadbalancer.cache.enabled=false");
        gateway = new SpringApplicationBuilder(ApiGatewayApplication.class, GatewayFixtureConfiguration.class)
                .web(WebApplicationType.REACTIVE).registerShutdownHook(false).run(arguments.toArray(String[]::new));
    }

    private List<String> commonArguments(ModuleConfiguration configuration, WebApplicationType type) {
        return new ArrayList<>(List.of(
                "--spring.config.location=" + configuration.locations(),
                "--spring.profiles.active=local-auth",
                "--spring.main.web-application-type=" + type.name().toLowerCase(java.util.Locale.ROOT),
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.jmx.enabled=false",
                "--eureka.client.enabled=false",
                "--spring.cloud.service-registry.auto-registration.enabled=false",
                "--spring.mongodb.uri=" + mongo.getReplicaSetUrl("messaging_db"),
                "--spring.rabbitmq.host=" + rabbit.getHost(),
                "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbit.getAdminPassword(),
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + users.baseUrl() + "/"));
    }

    public void assertProductionConfiguration() {
        assertThat(messaging).hasSize(2);
        for (Map.Entry<String, ConfigurableApplicationContext> entry : messaging.entrySet()) {
            ConfigurableApplicationContext context = entry.getValue();
            ConfigurableEnvironment environment = context.getEnvironment();
            assertModuleProperty(environment, messagingConfiguration.base(), "spring.application.name", SERVICE);
            assertModuleProperty(environment, messagingConfiguration.base(), "rebyte.realtime.push-channel",
                    "rebyte:messaging:push");
            assertModuleProperty(environment, messagingConfiguration.base(), "rebyte.realtime.heartbeat-ms", "10000");
            assertModuleProperty(environment, messagingConfiguration.base(), "rebyte.media.chat-prefix", "chat/");
            Map<String, String> outbox = Map.of(
                    "relay.fixed-delay-ms", "1000", "relay.batch-size", "100",
                    "relay.max-batches-per-tick", "50", "relay.confirm-timeout-ms", "5000",
                    "relay.lag-threshold-seconds", "60", "relay.lease-seconds", "60",
                    "reaper.fixed-delay-ms", "120000", "purge.retention-days", "7", "purge.cron", "0 0 3 * * *");
            outbox.forEach((key, value) -> assertModuleProperty(environment, messagingConfiguration.base(),
                    "rebyte.outbox." + key, value));
            assertSourceProperty(environment, messagingConfiguration.base(), "spring.mongodb.uri",
                    "${MONGO_URI:mongodb://localhost:27017/messaging_db?replicaSet=rs0}");
            assertSourceProperty(environment, messagingConfiguration.localAuth(),
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    "${USER_SERVICE_ISSUER:http://localhost:8081/}");
            assertThat(environment.getProperty("spring.config.location")).isEqualTo(messagingConfiguration.locations());
            assertThat(environment.getActiveProfiles()).containsExactly("local-auth");
            assertThat(environment.getProperty("eureka.instance.instance-id")).isEqualTo(entry.getKey());
            assertThat(context.getBean(MongoTemplate.class).getDb().getName()).isEqualTo("messaging_db");
            assertThat(context.getBean(SessionRegistry.class)).isNotNull();
            assertThat(context.getBean(StompAuthInterceptor.class)).isNotNull();
            assertThat(context.getBean(SimpMessagingTemplate.class)).isNotNull();
            assertThat(context.getBean(SimpUserRegistry.class)).isNotNull();
            assertThat(context.getBeansOfType(WebSocketMessageBrokerConfigurer.class)).containsKey("webSocketConfig");
            assertThat(context.getBean("redisPushListener", RedisMessageListenerContainer.class).isRunning()).isTrue();
            assertThat(context.getBean("subProtocolWebSocketHandler")).isNotNull();
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
        }
        ConfigurableEnvironment environment = gateway.getEnvironment();
        assertModuleProperty(environment, gatewayConfiguration.base(), "spring.application.name", "api-gateway");
        assertThat(environment.getProperty("spring.config.location")).isEqualTo(gatewayConfiguration.locations());
        assertSourceProperty(environment, gatewayConfiguration.localAuth(),
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "${USER_SERVICE_ISSUER:http://localhost:8081/}");
        assertThat(environment.getActiveProfiles()).containsExactly("local-auth");
        var routes = gateway.getBean(RouteLocator.class).getRoutes().collectList().block(Duration.ofSeconds(5));
        assertThat(routes).filteredOn(route -> route.getId().equals("messaging-ws")).singleElement()
                .satisfies(route -> assertThat(route.getUri().toString()).isEqualTo("lb:ws://messaging-service"));
        String routeIndex = null;
        for (int index = 0; index < routes.size(); index++) {
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
            if ("messaging-ws".equals(environment.getProperty(prefix + ".id"))) {
                routeIndex = prefix;
                break;
            }
        }
        assertThat(routeIndex).as("production messaging-ws route configuration").isNotNull();
        assertModuleProperty(environment, gatewayConfiguration.base(), routeIndex + ".uri", "lb:ws://messaging-service");
        assertThat(gateway.getBean(SimpleDiscoveryClient.class).getInstances(SERVICE))
                .extracting(instance -> instance.getPort()).containsExactlyInAnyOrderElementsOf(
                        messaging.values().stream().map(GatewayMessagingCluster::port).toList());
    }

    private static void assertModuleProperty(ConfigurableEnvironment environment, URL resource, String key,
            String expected) {
        assertThat(environment.getProperty(key)).as(key).isEqualTo(expected);
        PropertySource<?> source = effectiveSource(environment, key);
        assertOrigin(source, key, resource);
    }

    private static PropertySource<?> effectiveSource(ConfigurableEnvironment environment, String key) {
        return environment.getPropertySources().stream()
                .filter(source -> !source.getName().equals("configurationProperties") && source.containsProperty(key))
                .findFirst().orElseThrow(() -> new AssertionError("Missing configuration property " + key));
    }

    private static void assertSourceProperty(ConfigurableEnvironment environment, URL resource, String key,
            String expected) {
        PropertySource<?> source = environment.getPropertySources().stream().filter(candidate -> {
            Origin origin = OriginLookup.getOrigin(candidate, key);
            return origin instanceof TextResourceOrigin text && resourceUrl(text).equals(resource.toExternalForm())
                    && !candidate.getName().equals("configurationProperties");
        }).findFirst().orElseThrow(() -> new AssertionError("Missing module resource property " + key));
        assertThat(source.getProperty(key)).as("module-file value of %s", key).isEqualTo(expected);
        assertOrigin(source, key, resource);
    }

    private static void assertOrigin(PropertySource<?> source, String key, URL expected) {
        assertThat(OriginLookup.getOrigin(source, key)).as("origin of %s", key)
                .isInstanceOfSatisfying(TextResourceOrigin.class,
                        origin -> assertThat(resourceUrl(origin)).isEqualTo(expected.toExternalForm()));
    }

    private static String resourceUrl(TextResourceOrigin origin) {
        try {
            return origin.getResource().getURL().toExternalForm();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot resolve configuration origin", failure);
        }
    }

    public String httpUrl() {
        return "http://127.0.0.1:" + port(gateway);
    }

    public String wsUrl() {
        return "ws://127.0.0.1:" + port(gateway) + "/ws";
    }

    public List<String> instanceIds() {
        return instanceIds;
    }

    public List<String> activeInstanceIds() {
        return List.copyOf(messaging.keySet());
    }

    public void listing(String listingId, String sellerSub) {
        catalog.respond("/internal/listings/" + listingId, 200,
                "{\"id\":\"" + listingId + "\",\"sellerSub\":\"" + sellerSub
                        + "\",\"listingTitle\":\"Monitor\",\"price\":80.00,\"type\":\"HARDWARE\",\"available\":true}");
    }

    public void user(String sub) {
        users.respond("/internal/users/" + sub, 200,
                "{\"sub\":\"" + sub + "\",\"username\":\"" + sub + "\"}");
    }

    public void awaitWebSocketSlot() {
        // Keep the production 2/s upgrade limiter active without exhausting it in the auth matrix.
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(2))
                .until(() -> System.nanoTime() - lastWebSocketAttempt >= Duration.ofMillis(600).toNanos());
        lastWebSocketAttempt = System.nanoTime();
    }

    public int sessionCount(String sub) {
        return messaging.values().stream().mapToInt(context -> sessions(context, sub).size()).sum();
    }

    public int subscribedSessionCount(String sub, List<String> destinations) {
        return messaging.values().stream().mapToInt(context -> subscribedSessions(context, sub, destinations).size())
                .sum();
    }

    public List<String> subscribedInstances(String sub, List<String> destinations) {
        return messaging.entrySet().stream()
                .filter(entry -> !subscribedSessions(entry.getValue(), sub, destinations).isEmpty())
                .map(Map.Entry::getKey).toList();
    }

    private static List<SimpSession> sessions(ConfigurableApplicationContext context, String sub) {
        var user = context.getBean(SimpUserRegistry.class).getUser(sub);
        return user == null ? List.of() : List.copyOf(user.getSessions());
    }

    private static List<SimpSession> subscribedSessions(ConfigurableApplicationContext context, String sub,
            List<String> destinations) {
        SessionRegistry registry = context.getBean(SessionRegistry.class);
        // The expiry registry exposes no read API; correlate broker IDs with the actual decorated transports.
        Map<?, ?> transports = (Map<?, ?>) ReflectionTestUtils.getField(registry, "sessions");
        assertThat(transports).isNotNull();
        return sessions(context, sub).stream().filter(session -> {
            List<String> subscriptions = session.getSubscriptions().stream()
                    .map(subscription -> subscription.getDestination()).toList();
            if (!subscriptions.containsAll(destinations)) {
                return false;
            }
            assertThat(transports.containsKey(session.getId()))
                    .as("actual transport for broker session %s", session.getId()).isTrue();
            return true;
        }).toList();
    }

    public void publishProbe(String sub, String probeId) {
        messaging.values().iterator().next().getBean(RealtimePublisher.class)
                .push(sub, RealtimePublisher.UNREAD, Map.of("probe", probeId));
    }

    public List<UpgradeRequest> upgrades() {
        return messaging.values().stream().flatMap(context -> context.getBean(RequestRecorder.class).upgrades.stream())
                .sorted(java.util.Comparator.comparingLong(UpgradeRequest::observedAt)).toList();
    }

    public List<RestRequest> restRequests() {
        return messaging.values().stream().flatMap(context -> context.getBean(RequestRecorder.class).rest.stream())
                .sorted(java.util.Comparator.comparingLong(RestRequest::observedAt)).toList();
    }

    public void closeMessaging(String instanceId) {
        ConfigurableApplicationContext context = messaging.remove(instanceId);
        assertThat(context).as("instance to close").isNotNull();
        int stoppedPort = port(context);
        closeMessagingContext(context);
        SimpleDiscoveryProperties discovery = gateway.getBean(SimpleDiscoveryProperties.class);
        var survivors = discovery.getInstances().get(SERVICE).stream()
                .filter(instance -> instance.getPort() != stoppedPort).toList();
        discovery.getInstances().put(SERVICE, survivors);
        gateway.getBean(SimpleReactiveDiscoveryProperties.class).setInstances(Map.of(SERVICE, survivors));
        assertThat(gateway.getBean(SimpleDiscoveryClient.class).getInstances(SERVICE))
                .extracting(instance -> instance.getPort()).doesNotContain(stoppedPort);
        assertThat(gateway.getBean(SimpleReactiveDiscoveryClient.class).getInstances(SERVICE)
                .collectList().block(Duration.ofSeconds(5)))
                .extracting(instance -> instance.getPort()).doesNotContain(stoppedPort);
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static void closeMessagingContext(ConfigurableApplicationContext context) {
        ConcurrentTaskScheduler scheduler = context.getBean("taskScheduler", ConcurrentTaskScheduler.class);
        try {
            context.close();
        } finally {
            shutdownScheduler(scheduler);
        }
    }

    private static void shutdownScheduler(ConcurrentTaskScheduler scheduler) {
        // ConcurrentTaskScheduler does not own the executor created by production SchedulingConfig.
        ExecutorService executor = (ExecutorService) scheduler.getConcurrentExecutor();
        executor.shutdownNow();
        try {
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted closing messaging scheduler", failure);
        }
    }

    @Override
    public void close() {
        List<AutoCloseable> resources = new ArrayList<>();
        if (gateway != null) {
            resources.add(gateway);
            gateway = null;
        }
        for (ConfigurableApplicationContext context : new ArrayList<>(messaging.values()).reversed()) {
            resources.add(() -> closeMessagingContext(context));
        }
        messaging.clear();
        if (users != null) resources.add(users);
        if (catalog != null) resources.add(catalog);
        resources.add(rabbit);
        resources.add(redis);
        resources.add(mongo);
        RuntimeException failure = null;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) failure = new IllegalStateException("Cannot close gateway messaging cluster");
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private record ModuleConfiguration(URL base, URL localAuth) {
        private static ModuleConfiguration forApplication(Class<?> application) {
            URL codeSource = application.getProtectionDomain().getCodeSource().getLocation();
            try {
                boolean directory = codeSource.getProtocol().equals("file")
                        && Files.isDirectory(Path.of(codeSource.toURI()));
                URL base = directory ? new URL(codeSource, "application.yml")
                        : new URL("jar:" + codeSource.toExternalForm() + "!/application.yml");
                URL localAuth = directory ? new URL(codeSource, "application-local-auth.yml")
                        : new URL("jar:" + codeSource.toExternalForm() + "!/application-local-auth.yml");
                for (URL resource : List.of(base, localAuth)) {
                    var connection = resource.openConnection();
                    connection.setUseCaches(false);
                    try (InputStream stream = connection.getInputStream()) {
                        if (stream.read() == -1) {
                            throw new IllegalStateException("Empty required module configuration " + resource);
                        }
                    }
                }
                return new ModuleConfiguration(base, localAuth);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot resolve required configuration for " + application.getName(),
                        failure);
            }
        }

        private String locations() {
            return base.toExternalForm() + "," + localAuth.toExternalForm();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestJwtSupport.class)
    public static class MessagingFixtureConfiguration {
        /**
         * WebFlux on this shared classpath gives each messaging context a Reactor resource factory; with global
         * resources, closing one messaging instance would dispose the event loops the gateway server runs on.
         */
        @Bean
        ReactorResourceFactory reactorResourceFactory() {
            ReactorResourceFactory factory = new ReactorResourceFactory();
            factory.setUseGlobalResources(false);
            return factory;
        }

        @Bean
        DisposableBean messagingSchedulerCleanup(@Qualifier("taskScheduler") TaskScheduler scheduler) {
            // Failed startup also closes the context, before it can be entered in the cluster's map.
            return () -> shutdownScheduler((ConcurrentTaskScheduler) scheduler);
        }

        @Bean
        RequestRecorder requestRecorder(Environment environment) {
            return new RequestRecorder(environment.getRequiredProperty("eureka.instance.instance-id"));
        }

        @Bean
        FilterRegistrationBean<RequestRecorder> requestRecorderFilter(RequestRecorder recorder) {
            FilterRegistrationBean<RequestRecorder> registration = new FilterRegistrationBean<>(recorder);
            registration.setOrder(Integer.MIN_VALUE);
            return registration;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestJwtSupport.class)
    public static class GatewayFixtureConfiguration {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder(JwtDecoder decoder) {
            return token -> Mono.fromCallable(() -> decoder.decode(token));
        }
    }

    private static final class RequestRecorder extends OncePerRequestFilter {
        private final String instanceId;
        private final List<UpgradeRequest> upgrades = new CopyOnWriteArrayList<>();
        private final List<RestRequest> rest = new CopyOnWriteArrayList<>();

        private RequestRecorder(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                upgrades.add(new UpgradeRequest(instanceId, request.getHeader("Authorization"), System.nanoTime()));
            } else if (request.getRequestURI().startsWith("/conversations")) {
                rest.add(new RestRequest(instanceId, request.getMethod(), request.getRequestURI(), System.nanoTime()));
            }
            response.setHeader(INSTANCE_HEADER, instanceId);
            chain.doFilter(request, response);
        }
    }

    public record UpgradeRequest(String instanceId, String authorization, long observedAt) {
    }

    public record RestRequest(String instanceId, String method, String path, long observedAt) {
    }
}
