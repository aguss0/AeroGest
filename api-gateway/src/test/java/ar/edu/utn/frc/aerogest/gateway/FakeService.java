package ar.edu.utn.frc.aerogest.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Microservicio falso (servidor HTTP del JDK) para probar el ruteo del gateway sin levantar
 * Flight ni Reservation. Registra lo que recibe y responde 200 con un JSON que dice quién es;
 * si la ruta contiene "/slow" tarda más que el response-timeout del gateway.
 */
final class FakeService implements AutoCloseable {

    record Received(String method, String path, Headers headers) {
    }

    private final String name;
    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    FakeService(String name) {
        this.name = name;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> received() {
        return received;
    }

    void reset() {
        received.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        String path = exchange.getRequestURI().getPath();
        received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestHeaders()));
        try {
            if (path.contains("/slow")) {
                Thread.sleep(3_000);
            }
            byte[] bytes = ("{\"service\":\"" + name + "\",\"path\":\"" + path + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException | IOException e) {
            // el gateway cortó por timeout: es lo esperado en esa prueba
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
