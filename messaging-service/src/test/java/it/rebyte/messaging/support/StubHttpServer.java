package it.rebyte.messaging.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final Map<String, Queue<Response>> once = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start stub HTTP server", exception);
        }
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "stub-http-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(String path, int status, String jsonBody) {
        respond(path, status, jsonBody, 0);
    }

    public void respond(String path, int status, String jsonBody, long delayMillis) {
        responses.put(key(path), new Response(status, jsonBody, delayMillis));
    }

    public void respondOnce(String path, int status, String jsonBody) {
        once.computeIfAbsent(key(path), ignored -> new ConcurrentLinkedQueue<>())
                .add(new Response(status, jsonBody, 0));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        requests.add(new Request(uri.getPath(), uri.getRawQuery()));
        String requestKey = key(uri.toString());
        Queue<Response> pending = once.get(requestKey);
        Response response = pending == null ? null : pending.poll();
        if (response == null) {
            response = responses.get(requestKey);
        }
        if (response == null) {
            response = responses.get(uri.getPath());
        }
        if (response == null) {
            write(exchange, 404, "{}");
            return;
        }
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                write(exchange, 500, "{}");
                return;
            }
        }
        write(exchange, response.status(), response.jsonBody());
    }

    private static String key(String path) {
        int queryStart = path.indexOf('?');
        if (queryStart < 0) {
            return path;
        }
        String[] parameters = path.substring(queryStart + 1).split("&");
        Arrays.sort(parameters);
        return path.substring(0, queryStart) + "?" + String.join("&", parameters);
    }

    private static void write(HttpExchange exchange, int status, String jsonBody) throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    public record Request(String path, String query) {
    }

    private record Response(int status, String jsonBody, long delayMillis) {
    }
}
