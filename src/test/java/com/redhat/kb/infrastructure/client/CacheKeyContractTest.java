package com.redhat.kb.infrastructure.client;

import java.lang.reflect.Method;

import io.quarkus.cache.CacheResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shape of the cached methods on {@link KnowledgeBaseClient}.
 *
 * <p>Quarkus builds a cache key from a method's parameters, so per-subscription isolation
 * holds only while the credential remains one of them. Dropping it — or caching a method
 * that does not take one — would silently let one caller be served content fetched with
 * another's entitlements, and no behavioural test would fail. This test fails instead.
 */
class CacheKeyContractTest {

    @Test
    @DisplayName("every cached method takes the credential as its first parameter")
    void cachedMethodsAreKeyedByCredential() {
        long cachedMethods = 0;

        for (Method method : KnowledgeBaseClient.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(CacheResult.class)) {
                continue;
            }
            cachedMethods++;

            Class<?>[] parameters = method.getParameterTypes();
            assertTrue(parameters.length > 0,
                    method.getName() + " is cached but takes no parameters, so every caller "
                            + "would share a single entry");
            assertEquals(RedHatCredential.class, parameters[0],
                    method.getName() + " must take the credential as its first parameter, "
                            + "otherwise its cache is shared across subscriptions");
        }

        assertEquals(2, cachedMethods,
                "expected search and getSolution to be cached; a new cached method must be "
                        + "keyed by credential too");
    }

    @Test
    @DisplayName("cached methods use separate caches for searches and articles")
    void cachedMethodsUseDistinctCaches() {
        // Sharing one cache between the two would let a search key collide with an article
        // key, which is a subtler route to the same cross-subscription leak.
        long distinctCaches = java.util.Arrays.stream(KnowledgeBaseClient.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(CacheResult.class))
                .map(m -> m.getAnnotation(CacheResult.class).cacheName())
                .distinct()
                .count();

        assertEquals(2, distinctCaches);
    }
}
