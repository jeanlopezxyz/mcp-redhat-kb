package com.redhat.kb.infrastructure.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * Shared HTTP scaffolding for the Red Hat API clients: one {@link HttpClient} with the
 * configured timeouts, a response-size bound enforced <em>while streaming</em>, and the
 * common failure-to-message mapping.
 *
 * <p>The clients keep only what is theirs — the URL, input validation and the shape of
 * their response — so a fix here (timeouts, the size bound, error wrapping) applies to all
 * of them at once instead of having to be repeated three times.
 */
@ApplicationScoped
public class BoundedJsonHttp {

    /**
     * How one upstream API is bounded and described when it fails.
     *
     * <p>The messages are explicit strings rather than a generated pattern because they
     * reach the MCP client verbatim: naming the exact API ("Knowledge Base", "Security
     * Data", "Product Life Cycle") is what lets a model tell the caller which service is
     * misbehaving.
     *
     * @param maxResponseBytes upper bound on a response body; anything larger is refused
     * @param interruptedMessage shown when the calling thread is interrupted mid-request
     * @param unreachableMessage shown when the API cannot be reached or the body cut off
     * @param oversizedMessage shown when the body exceeds {@code maxResponseBytes}
     * @param malformedMessage shown when a 200 body is not the JSON the client expected
     */
    public record ApiProfile(int maxResponseBytes,
            String interruptedMessage,
            String unreachableMessage,
            String oversizedMessage,
            String malformedMessage) {
    }

    /**
     * Status and body of a bounded response. Bodies of non-2xx replies are never read:
     * the clients deliberately exclude them from error messages (they can echo tokens or
     * internal detail), so downloading them would only be attack surface.
     */
    public record ApiResponse(int status, String body) {

        public boolean isOk() {
            return status == Response.Status.OK.getStatusCode();
        }
    }

    private final RedHatApiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public BoundedJsonHttp(RedHatApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
                .build();
    }

    /**
     * Issues a GET and returns the bounded response.
     *
     * <p>The size bound is enforced before the body is buffered, never after: the body is
     * streamed and reading stops at {@code maxResponseBytes + 1}, so an oversized response
     * can occupy at most one byte over the limit of heap regardless of its actual size.
     * When the server declares a {@code Content-Length} beyond the limit, the response is
     * refused without reading the body at all.
     *
     * @param headers alternating header names and values, as in
     *        {@link HttpRequest.Builder#headers(String...)}
     * @throws KnowledgeBaseException with the profile's message when the request is
     *         interrupted, the API is unreachable, or the body exceeds the bound
     */
    public ApiResponse get(String url, ApiProfile api, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()));
        if (headers.length > 0) {
            request.headers(headers);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException(api.interruptedMessage());
        } catch (IOException e) {
            throw new KnowledgeBaseException(api.unreachableMessage());
        }

        try (InputStream body = response.body()) {
            if (!isSuccess(response.statusCode())) {
                // Closing without reading cancels the transfer server-side.
                return new ApiResponse(response.statusCode(), "");
            }
            rejectDeclaredOversize(response, api);
            return new ApiResponse(response.statusCode(), readBounded(body, api));
        } catch (KnowledgeBaseException e) {
            // Caught and rethrown ahead of IOException on purpose. Abandoning a body that
            // is still arriving makes close() fail, and try-with-resources attaches that
            // IOException to whatever was already in flight -- so the size refusal below
            // would otherwise be reported as "could not reach the API", hiding the real
            // reason from the model and blaming the network for a working server.
            throw e;
        } catch (IOException e) {
            // The connection dropped mid-body (or on close); to the caller that is the
            // same failure mode as never reaching the API.
            throw new KnowledgeBaseException(api.unreachableMessage());
        }
    }

    /**
     * Deserializes a response body into the given type, mapping parse failures to the
     * profile's malformed-response message.
     */
    public <T> T readValue(ApiResponse response, Class<T> type, ApiProfile api) {
        try {
            return objectMapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new KnowledgeBaseException(api.malformedMessage());
        }
    }

    /**
     * Parses a response body as a JSON tree, mapping parse failures to the profile's
     * malformed-response message.
     */
    public JsonNode readTree(ApiResponse response, ApiProfile api) {
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new KnowledgeBaseException(api.malformedMessage());
        }
    }

    private static boolean isSuccess(int status) {
        return status == Response.Status.OK.getStatusCode();
    }

    /**
     * Refuses early when the server itself declares a body beyond the bound, so not a
     * single body byte is transferred.
     */
    private static void rejectDeclaredOversize(HttpResponse<InputStream> response, ApiProfile api) {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > api.maxResponseBytes()) {
            throw new KnowledgeBaseException(api.oversizedMessage());
        }
    }

    /**
     * Reads at most one byte past the bound: enough to distinguish a body exactly at the
     * limit (accepted) from one over it (refused), without ever buffering the excess.
     */
    private static String readBounded(InputStream body, ApiProfile api) throws IOException {
        byte[] bytes = body.readNBytes(api.maxResponseBytes() + 1);
        if (bytes.length > api.maxResponseBytes()) {
            throw new KnowledgeBaseException(api.oversizedMessage());
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
