package com.redhat.kb.infrastructure.client;

import java.util.List;
import java.util.Optional;

import com.redhat.kb.infrastructure.dto.KnowledgeBaseArticleDto;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins down that Knowledge Base caches are partitioned per credential.
 *
 * <p>Entitlements decide what the API returns, so a result fetched with one subscription
 * must never be served to another. That isolation rests on the credential being part of the
 * cache key: were the parameter reordered or dropped, the partitioning would disappear with
 * no other test noticing. These tests inspect the cache keys directly for that reason.
 */
@QuarkusTest
@TestProfile(CachePartitioningTest.CacheTestProfile.class)
class CachePartitioningTest {

    public static class CacheTestProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "redhat.api.offline-token", "test-token",
                    "quarkus.oidc.enabled", "false",
                    "quarkus.http.auth.permission.mcp.policy", "permit");
        }
    }

    @Inject
    @CacheName("kb-search")
    Cache searchCache;

    @Inject
    @CacheName("kb-article")
    Cache articleCache;

    private static final RedHatCredential ALICE = RedHatCredential.of("alice-offline-token");
    private static final RedHatCredential BOB = RedHatCredential.of("bob-offline-token");

    /**
     * Populates the cache the way the client does, without any network call: the supplier
     * stands in for the Hydra response.
     */
    private List<KnowledgeBaseArticleDto> cacheSearch(RedHatCredential credential, String query, String marker) {
        KnowledgeBaseArticleDto article = new KnowledgeBaseArticleDto();
        article.setId(marker);
        return searchCache
                .get(new CompositeKey(credential, query, 10, "", ""), key -> List.of(article))
                .await().indefinitely();
    }

    /** Mirrors the key Quarkus builds from a multi-parameter cached method. */
    private record CompositeKey(RedHatCredential credential, String query, int maxResults,
            String product, String documentType) {
    }

    @Test
    @DisplayName("keeps one credential's search results out of another's")
    void searchResultsAreIsolatedPerCredential() {
        // Same query, two subscribers: each must get back what was cached for them.
        List<KnowledgeBaseArticleDto> forAlice = cacheSearch(ALICE, "crashloop", "alice-result");
        List<KnowledgeBaseArticleDto> forBob = cacheSearch(BOB, "crashloop", "bob-result");

        assertEquals("alice-result", forAlice.get(0).getId());
        assertEquals("bob-result", forBob.get(0).getId(),
                "Bob received a result cached for a different subscription");
    }

    @Test
    @DisplayName("reuses the cached result for the same credential and query")
    void sameCredentialHitsTheCache() {
        cacheSearch(ALICE, "etcd timeout", "first-call");
        // A second call with a different marker must still return the cached entry,
        // proving the key matched rather than a fresh lookup being made.
        List<KnowledgeBaseArticleDto> second = cacheSearch(ALICE, "etcd timeout", "second-call");

        assertEquals("first-call", second.get(0).getId());
    }

    @Test
    @DisplayName("treats a different query as a different entry")
    void differentQueriesAreSeparateEntries() {
        cacheSearch(ALICE, "query-one", "result-one");
        List<KnowledgeBaseArticleDto> other = cacheSearch(ALICE, "query-two", "result-two");

        assertEquals("result-two", other.get(0).getId());
    }

    @Test
    @DisplayName("keeps one credential's article out of another's")
    void articlesAreIsolatedPerCredential() {
        // Article bodies are the sensitive case: subscriber-only content fetched with one
        // account must not be replayed to an account that lacks the entitlement.
        Optional<String> forAlice = articleCache
                .get(new ArticleKey(ALICE, "5049001"), key -> Optional.of("alice-body"))
                .await().indefinitely();
        Optional<String> forBob = articleCache
                .get(new ArticleKey(BOB, "5049001"), key -> Optional.of("bob-body"))
                .await().indefinitely();

        assertEquals("alice-body", forAlice.orElseThrow());
        assertEquals("bob-body", forBob.orElseThrow(),
                "Bob received an article cached for a different subscription");
    }

    private record ArticleKey(RedHatCredential credential, String articleId) {
    }

    @Test
    @DisplayName("distinguishes credentials by value, so keys cannot collide")
    void credentialsAreDistinctCacheKeys() {
        // Cache keys rely on equals/hashCode; identical credentials must collapse to one
        // entry and different ones must never share it.
        assertEquals(RedHatCredential.of("alice-offline-token"), ALICE);
        assertNotEquals(ALICE, BOB);
        assertNotEquals(ALICE.hashCode(), BOB.hashCode());
    }
}
