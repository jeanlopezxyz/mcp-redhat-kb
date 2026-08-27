package com.redhat.kb.infrastructure.model;

import java.util.List;

import com.redhat.kb.infrastructure.model.KnowledgeBaseArticle;

/**
 * A page of search results together with how many matched overall.
 *
 * <p>The total matters to the caller: ten results out of ten means the search was precise,
 * while ten out of several thousand means the query was too broad. Returning only the page
 * makes those two cases indistinguishable.
 */
public record SearchPage(List<KnowledgeBaseArticle> articles, int totalFound) {

    public static SearchPage empty() {
        return new SearchPage(List.of(), 0);
    }

    public boolean isEmpty() {
        return articles.isEmpty();
    }
}
