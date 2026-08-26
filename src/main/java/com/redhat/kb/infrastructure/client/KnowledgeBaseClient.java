package com.redhat.kb.infrastructure.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.kb.infrastructure.config.RedHatApiConfig;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;
import com.redhat.kb.infrastructure.dto.KnowledgeBaseSearchResponseDto;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import static com.redhat.kb.KnowledgeBaseConstants.DEFAULT_MAX_RESULTS;

/**
 * HTTP client for Red Hat Knowledge Base (Hydra API).
 * Enables searching articles, solutions, and technical documentation.
 */
@ApplicationScoped
public class KnowledgeBaseClient {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HYDRA_BASE_URL = "https://access.redhat.com/hydra/rest/search/kcs";

    private static final String SEARCH_FIELDS = "id,title,abstract,documentKind,view_uri,product,lastModifiedDate";
    private static final String DETAIL_FIELDS = "id,title,abstract,documentKind,view_uri,product,issue," +
            "solution_environment,solution_rootcause,solution_resolution,solution_diagnosticsteps," +
            "lastModifiedDate";

    /** Guards against an oversized response exhausting the heap. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final RedHatApiConfig config;
    private final RedHatAuthClient authClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public KnowledgeBaseClient(RedHatApiConfig config, RedHatAuthClient authClient, ObjectMapper objectMapper) {
        this.config = config;
        this.authClient = authClient;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeouts().connectSeconds()))
                .build();
    }

    // Note on caching: the credential is the first parameter of both cached methods, so
    // Quarkus includes it in the cache key. Entries are therefore partitioned per
    // credential — a result fetched with one subscription is never served to another,
    // which matters because entitlements decide what the API returns.

    /**
     * Searches articles in Red Hat Knowledge Base.
     *
     * <p>Free-text and filter values are Lucene-escaped: URL encoding alone would still let
     * the value reach Solr as query syntax and alter the search semantics.
     */
    @CacheResult(cacheName = "kb-search")
    public SearchPage search(RedHatCredential credential, String query, int maxResults,
            String product, String documentType) {
        StringBuilder urlBuilder = new StringBuilder(HYDRA_BASE_URL);
        urlBuilder.append("?q=").append(encode(SolrQuery.escape(query)));
        urlBuilder.append("&rows=").append(maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS);
        urlBuilder.append("&fl=").append(SEARCH_FIELDS);

        if (product != null && !product.isBlank()) {
            // Matched as a phrase, so the value has to be the product's full name.
            urlBuilder.append("&fq=product:").append(encode("\"" + SolrQuery.escape(product) + "\""));
        }

        if (documentType != null && !documentType.isBlank()) {
            urlBuilder.append("&fq=documentKind:").append(encode("\"" + SolrQuery.escape(documentType) + "\""));
        }

        KnowledgeBaseSearchResponseDto response =
                execute(credential, urlBuilder.toString(), "search the Knowledge Base");

        return new SearchPage(extractDocs(response), extractTotal(response));
    }

    /**
     * Gets the full details of an article by its ID.
     */
    @CacheResult(cacheName = "kb-article")
    public Optional<KnowledgeBaseArticleDto> getArticle(RedHatCredential credential, String articleId) {
        if (!SolrQuery.isValidArticleId(articleId)) {
            throw new KnowledgeBaseException("Article ID must be numeric");
        }

        String url = HYDRA_BASE_URL
                + "?q=" + encode("id:" + articleId)
                + "&fl=" + DETAIL_FIELDS;

        return extractDocs(execute(credential, url, "fetch the article")).stream().findFirst();
    }

    /**
     * Issues the request and deserializes the response, mapping failures to a typed
     * exception whose message names the failure mode.
     */
    private KnowledgeBaseSearchResponseDto execute(RedHatCredential credential, String url, String action) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + authClient.getAccessToken(credential))
                    .GET()
                    .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()))
                    .build();

            response = httpClient.send(request, boundedBodyHandler());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException("Request to the Knowledge Base was interrupted");
        } catch (AuthenticationException e) {
            // Already carries a client-safe message.
            throw e;
        } catch (IOException e) {
            throw new KnowledgeBaseException("Could not reach the Red Hat Knowledge Base API");
        }

        int status = response.statusCode();
        if (status != Response.Status.OK.getStatusCode()) {
            throw new KnowledgeBaseException(describeFailure(status, action));
        }

        try {
            return objectMapper.readValue(response.body(), KnowledgeBaseSearchResponseDto.class);
        } catch (IOException e) {
            throw new KnowledgeBaseException("Received a malformed response from the Knowledge Base API");
        }
    }

    /**
     * Turns a status code into an actionable message. Response bodies are deliberately
     * excluded: this text reaches the MCP client.
     */
    private String describeFailure(int status, String action) {
        String reason = switch (status) {
            case 401 -> "authentication failed - REDHAT_TOKEN may be expired or invalid";
            case 403 -> "access denied - the article may require a subscription your account lacks";
            case 404 -> "the requested resource does not exist";
            case 429 -> "rate limit exceeded - retry in a few moments";
            default -> status >= 500
                    ? "the Red Hat Knowledge Base API is unavailable"
                    : "unexpected response";
        };
        return "Could not " + action + ": " + reason + " (HTTP " + status + ")";
    }

    /**
     * Reads the body into a string while refusing responses beyond {@link #MAX_RESPONSE_BYTES}.
     */
    private static HttpResponse.BodyHandler<String> boundedBodyHandler() {
        return info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArray(),
                bytes -> {
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new KnowledgeBaseException("Knowledge Base response exceeded the size limit");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                });
    }

    /**
     * Extracts the document list, tolerating a response that omits {@code response} or {@code docs}.
     */
    private static List<KnowledgeBaseArticleDto> extractDocs(KnowledgeBaseSearchResponseDto response) {
        return Optional.ofNullable(response)
                .map(KnowledgeBaseSearchResponseDto::getResponse)
                .map(KnowledgeBaseSearchResponseDto.Response::getDocs)
                .orElseGet(List::of);
    }

    /**
     * How many documents matched, which is usually far more than one page holds.
     */
    private static int extractTotal(KnowledgeBaseSearchResponseDto response) {
        return Optional.ofNullable(response)
                .map(KnowledgeBaseSearchResponseDto::getResponse)
                .map(KnowledgeBaseSearchResponseDto.Response::getNumFound)
                .orElse(0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
