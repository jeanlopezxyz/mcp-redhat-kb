package com.redhat.kb.infrastructure.client;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal local HTTP stub for the client tests, built on the JDK's own server so the
 * suite stays offline without adding a mock-server dependency.
 *
 * <p>Each instance binds an ephemeral loopback port, serves the single response configured
 * through one of the {@code respond*} methods, and records the last request so tests can
 * assert what the client actually sent.
 */
final class StubApiServer implements AutoCloseable {

    /** Serves one configured response for whatever request arrives. */
    @FunctionalInterface
    private interface Responder {
        void handle(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private volatile Responder responder;
    private volatile HttpExchange lastRequest;

    private StubApiServer(HttpServer server) {
        this.server = server;
    }

    static StubApiServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubApiServer stub = new StubApiServer(server);
            server.createContext("/", exchange -> {
                stub.lastRequest = exchange;
                try (exchange) {
                    stub.responder.handle(exchange);
                } catch (IOException e) {
                    // The client may hang up early on purpose (size-limit tests);
                    // that is not a stub failure.
                }
            });
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the stub server", e);
        }
    }

    /** Base URL the client under test should be pointed at. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Responds with the given status and body, declaring its exact Content-Length. */
    void respond(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        responder = exchange -> {
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            write(exchange, bytes);
        };
    }

    /**
     * Streams the body without declaring a Content-Length, so a client can only discover
     * the size by reading — the path a size bound must survive.
     */
    void respondChunked(int status, byte[] body) {
        responder = exchange -> {
            exchange.sendResponseHeaders(status, 0);
            write(exchange, body);
        };
    }

    /**
     * Declares an arbitrary Content-Length while sending only a token amount of body, so a
     * test can verify the client refuses an oversized response from the header alone. The
     * few bytes written force the headers onto the wire; closing without them can abort
     * the connection before the client even sees the status line.
     */
    void respondDeclaringLength(int status, long declaredLength) {
        responder = exchange -> {
            exchange.sendResponseHeaders(status, declaredLength);
            exchange.getResponseBody().write(new byte[16]);
            exchange.getResponseBody().flush();
        };
    }

    /** Path and query of the last request received, or empty when none arrived. */
    Optional<URI> lastRequestUri() {
        return Optional.ofNullable(lastRequest).map(HttpExchange::getRequestURI);
    }

    /** First value of the given header on the last request, or empty. */
    Optional<String> lastRequestHeader(String name) {
        return Optional.ofNullable(lastRequest)
                .map(exchange -> exchange.getRequestHeaders().getFirst(name));
    }

    private static void write(HttpExchange exchange, byte[] bytes) throws IOException {
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
