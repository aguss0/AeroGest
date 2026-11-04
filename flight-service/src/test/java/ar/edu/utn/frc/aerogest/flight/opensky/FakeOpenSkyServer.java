package ar.edu.utn.frc.aerogest.flight.opensky;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Servidor HTTP falso (JDK) que hace de OpenSky, para probar el cliente sin red. */
final class FakeOpenSkyServer implements AutoCloseable {

    record Received(String path, String query, String userAgent) {
    }

    record Reply(int status, String body, Map<String, String> headers, long delayMs) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply reply = Reply.of(404, "{}");

    FakeOpenSkyServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    void respondWith(Reply reply) {
        this.reply = reply;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    List<Received> received() {
        return received;
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("User-Agent")));
        Reply current = reply;
        try {
            if (current.delayMs() > 0) {
                Thread.sleep(current.delayMs());
            }
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            current.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(current.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException | IOException e) {
            // el cliente cortó por timeout: esperable en esa prueba
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
