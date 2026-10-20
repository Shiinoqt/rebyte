package it.rebyte.catalog.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;

@SpringBootTest
public class StubUserServiceIT extends MongoTestBase {

    private static final HttpServer SERVER = startServer();

    @DynamicPropertySource
    static void userServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url",
                () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @Autowired
    private UserServiceClient client;

    @Test
    void feignClientUsesTheInternalUserRouteAndDeserializesDeveloperFlag() {
        UserSummary summary = client.getSummary("developer-sub");

        assertThat(summary.sub()).isEqualTo("developer-sub");
        assertThat(summary.isDeveloper()).isTrue();
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/users/developer-sub", exchange -> {
                byte[] body = ("{\"sub\":\"developer-sub\",\"username\":\"dev\",\"avatar\":null,"
                        + "\"isDeveloper\":true,\"ratingAvg\":null,\"ratingCount\":0,\"completedSales\":3}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
