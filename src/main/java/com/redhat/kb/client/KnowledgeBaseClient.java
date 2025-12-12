package com.redhat.kb.client;

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
import com.redhat.kb.config.RedHatApiConfig;
import com.redhat.kb.dto.KnowledgeBaseArticleDto;
import com.redhat.kb.dto.KnowledgeBaseSearchResponseDto;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * Cliente HTTP para el Knowledge Base de Red Hat (Hydra API).
 * Permite buscar articulos, soluciones y documentacion tecnica.
 */
@ApplicationScoped
public class KnowledgeBaseClient {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HYDRA_BASE_URL = "https://access.redhat.com/hydra/rest/search/kcs";

    private static final String SEARCH_FIELDS = "id,title,abstract,documentKind,view_uri,product,lastModifiedDate";
    private static final String DETAIL_FIELDS = "id,title,abstract,documentKind,view_uri,product,issue," +
            "solution_environment,solution_rootcause,solution_resolution,solution_diagnosticsteps," +
            "lastModifiedDate,createdDate";

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

    /**
     * Busca articulos en el Knowledge Base de Red Hat.
     */
    public List<KnowledgeBaseArticleDto> search(String query, int maxResults, String product, String documentType) {
        try {
            String token = authClient.getAccessToken();

            StringBuilder urlBuilder = new StringBuilder(HYDRA_BASE_URL);
            urlBuilder.append("?q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            urlBuilder.append("&rows=").append(maxResults > 0 ? maxResults : 10);
            urlBuilder.append("&fl=").append(SEARCH_FIELDS);

            if (product != null && !product.isBlank()) {
                urlBuilder.append("&fq=product:").append(URLEncoder.encode("\"" + product + "\"", StandardCharsets.UTF_8));
            }

            if (documentType != null && !documentType.isBlank()) {
                urlBuilder.append("&fq=documentKind:").append(URLEncoder.encode("\"" + documentType + "\"", StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
                    .GET()
                    .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == Response.Status.OK.getStatusCode()) {
                KnowledgeBaseSearchResponseDto searchResponse =
                    objectMapper.readValue(response.body(), KnowledgeBaseSearchResponseDto.class);
                return searchResponse.getResponse() != null
                    ? searchResponse.getResponse().getDocs()
                    : List.of();
            } else {
                throw new RuntimeException("Error buscando en Knowledge Base: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error conectando con Hydra API", e);
        }
    }

    /**
     * Obtiene el detalle completo de una solucion por su ID.
     */
    public Optional<KnowledgeBaseArticleDto> getSolution(String solutionId) {
        try {
            String token = authClient.getAccessToken();

            String url = HYDRA_BASE_URL +
                "?q=" + URLEncoder.encode("id:" + solutionId, StandardCharsets.UTF_8) +
                "&fl=" + DETAIL_FIELDS;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token)
                    .GET()
                    .timeout(Duration.ofSeconds(config.timeouts().requestSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == Response.Status.OK.getStatusCode()) {
                KnowledgeBaseSearchResponseDto searchResponse =
                    objectMapper.readValue(response.body(), KnowledgeBaseSearchResponseDto.class);

                if (searchResponse.getResponse() != null &&
                    searchResponse.getResponse().getDocs() != null &&
                    !searchResponse.getResponse().getDocs().isEmpty()) {
                    return Optional.of(searchResponse.getResponse().getDocs().get(0));
                }
                return Optional.empty();
            } else {
                throw new RuntimeException("Error obteniendo solucion: " + response.statusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error conectando con Hydra API", e);
        }
    }
}
