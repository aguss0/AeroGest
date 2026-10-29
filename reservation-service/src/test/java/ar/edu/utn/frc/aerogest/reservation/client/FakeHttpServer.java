package ar.edu.utn.frc.aerogest.reservation.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Servidor HTTP falso (JDK) para probar los clientes sin red ni Flight/Keycloak reales. */
final class FakeHttpServer implements AutoCloseable {

    record Received(String method, String path, String body, com.sun.net.httpserver.Headers headers) {
    }

    record Reply(int status, String body, long delayMs) {
        static Reply of(int status, String body) {
            return new Reply(status, body, 0);
        }

        static Reply slow(int status, String body, long delayMs) {
            return new Reply(status, body, delayMs);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> handler = r -> Reply.of(404, "{}");

    FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    void respondWith(Function<Received, Reply> handler) {
        this.handler = handler;
    }

    void respondWith(Reply reply) {
        this.handler = r -> reply;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> received() {
        return received;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                body, exchange.getRequestHeaders());
        received.add(request);
        Reply reply = handler.apply(request);
        try {
            if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : bytes.length);
            if (reply.status() != 204) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (InterruptedException | IOException e) {
            // el cliente cortó (timeout): es esperable en los tests de lentitud
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
