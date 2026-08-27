package com.redhat.kb.infrastructure.client;

import com.redhat.kb.infrastructure.credential.AuthenticationException;
import com.redhat.kb.infrastructure.credential.RedHatAuthClient;
import com.redhat.kb.infrastructure.credential.RedHatCredential;
import com.redhat.kb.infrastructure.http.BoundedJsonHttp;
import com.redhat.kb.infrastructure.http.KnowledgeBaseException;
import com.redhat.kb.infrastructure.model.SearchPage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.redhat.kb.infrastructure.config.RedHatApiConfig;
import com.redhat.kb.infrastructure.model.KnowledgeBaseArticle;
import com.redhat.kb.infrastructure.model.KnowledgeBaseSearchResponse;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import static com.redhat.kb.KnowledgeBaseConstants.DEFAULT_MAX_RESULTS;

/**
 * HTTP client for Red Hat Knowledge Base (Hydra API).
 * Enables searching articles, solutions, and technical documentation.
 */
@ApplicationScoped
public class KnowledgeBaseClient {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String SEARCH_FIELDS = "id,title,abstract,documentKind,view_uri,product,lastModifiedDate";
    private static final String DETAIL_FIELDS = "id,title,abstract,documentKind,view_uri,product,issue," +
            "solution_environment,solution_rootcause,solution_resolution,solution_diagnosticsteps," +
            "lastModifiedDate";

    /** Most a response body may occupy; enforced while streaming, never after buffering. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** How this API is bounded and named in failure messages the MCP client sees. */
    private static final BoundedJsonHttp.ApiProfile API = new BoundedJsonHttp.ApiProfile(
            MAX_RESPONSE_BYTES,
            "Request to the Knowledge Base was interrupted",
            "Could not reach the Red Hat Knowledge Base API",
            "Knowledge Base response exceeded the size limit",
            "Received a malformed response from the Knowledge Base API");

    private final String baseUrl;
    private final RedHatAuthClient authClient;
    private final BoundedJsonHttp http;

    @Inject
    public KnowledgeBaseClient(RedHatApiConfig config, RedHatAuthClient authClient, BoundedJsonHttp http) {
        this.baseUrl = config.urls().knowledgeBase();
        this.authClient = authClient;
        this.http = http;
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
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("?q=").append(encode(SolrQuery.escape(query)));
        urlBuilder.append("&rows=").append(maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS);
        urlBuilder.append("&fl=").append(SEARCH_FIELDS);
        // Documentation is published in several languages under the same title, so without
        // this a page of results is padded with Japanese and Korean copies of one article.
        urlBuilder.append("&fq=").append(encode("language:en"));

        if (product != null && !product.isBlank()) {
            // Matched as a phrase, so the value has to be the product's full name.
            urlBuilder.append("&fq=product:").append(encode("\"" + SolrQuery.escape(product) + "\""));
        }

        if (documentType != null && !documentType.isBlank()) {
            urlBuilder.append("&fq=documentKind:").append(encode("\"" + SolrQuery.escape(documentType) + "\""));
        }

        KnowledgeBaseSearchResponse response =
                execute(credential, urlBuilder.toString(), "search the Knowledge Base");

        return new SearchPage(extractDocs(response), extractTotal(response));
    }

    /**
     * Gets the full details of an article by its ID.
     */
    @CacheResult(cacheName = "kb-article")
    public Optional<KnowledgeBaseArticle> getArticle(RedHatCredential credential, String articleId) {
        if (!SolrQuery.isValidArticleId(articleId)) {
            throw new KnowledgeBaseException(
                    "Article ID must be numeric (e.g. 7136675) or an advisory (e.g. RHSA-2026:6565)");
        }

        // `q=id:<n>` is scored free-text search, not a lookup: Hydra answers an unknown id
        // with ten unrelated articles, and taking the first one hands the model a different
        // article than the one asked for. `fq` filters exactly and returns nothing when the
        // id does not exist.
        //
        // An id is not unique either: 33098 matches seven documents — one Solution, five
        // translations of a Vulnerability, and an empty Certification stub. Restricting to
        // English drops the translations, and the ranking below picks the one that carries
        // an answer rather than whichever Hydra happened to list first.
        //
        // The id is quoted because an advisory carries a colon (RHSA-2026:6565), which Solr
        // would otherwise read as the start of another field.
        String url = baseUrl
                + "?q=" + encode("*:*")
                + "&fq=" + encode("id:\"" + articleId + "\"")
                + "&fq=" + encode("language:en")
                + "&fl=" + DETAIL_FIELDS;

        return extractDocs(execute(credential, url, "fetch the article")).stream()
                // Belt and braces: never return an article whose id is not the one asked
                // for, whatever the filter did upstream.
                .filter(article -> articleId.equals(article.getId()))
                .max(Comparator.comparingInt(KnowledgeBaseClient::readableContent));
    }

    /**
     * Scores how much of an answer a document carries, to break a tie between records
     * sharing an id. A resolution outranks a problem statement, which outranks a bare
     * title: the alternative is returning whichever one Hydra listed first, which for
     * id 33098 is an empty Certification stub.
     */
    private static int readableContent(KnowledgeBaseArticle article) {
        int score = 0;
        if (article.getSolutionResolution() != null) {
            score += 4;
        }
        if (article.getIssue() != null) {
            score += 2;
        }
        if (article.getTitle() != null && !article.getTitle().isBlank()) {
            score += 1;
        }
        return score;
    }

    /**
     * Issues the request and deserializes the response, mapping failures to a typed
     * exception whose message names the failure mode.
     */
    private KnowledgeBaseSearchResponse execute(RedHatCredential credential, String url, String action) {
        // Resolved before the request so an SSO failure surfaces as its own
        // AuthenticationException rather than as a Knowledge Base one.
        String accessToken = authClient.getAccessToken(credential);

        BoundedJsonHttp.ApiResponse response = http.get(url, API,
                HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken);

        if (!response.isOk()) {
            throw new KnowledgeBaseException(describeFailure(response.status(), action));
        }

        return http.readValue(response, KnowledgeBaseSearchResponse.class, API);
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
     * Extracts the document list, tolerating a response that omits {@code response} or {@code docs}.
     */
    private static List<KnowledgeBaseArticle> extractDocs(KnowledgeBaseSearchResponse response) {
        return Optional.ofNullable(response)
                .map(KnowledgeBaseSearchResponse::getResponse)
                .map(KnowledgeBaseSearchResponse.Response::getDocs)
                .orElseGet(List::of);
    }

    /**
     * How many documents matched, which is usually far more than one page holds.
     */
    private static int extractTotal(KnowledgeBaseSearchResponse response) {
        return Optional.ofNullable(response)
                .map(KnowledgeBaseSearchResponse::getResponse)
                .map(KnowledgeBaseSearchResponse.Response::getNumFound)
                .orElse(0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
