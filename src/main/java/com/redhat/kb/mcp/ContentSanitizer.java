package com.redhat.kb.mcp;

import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import static com.redhat.kb.KnowledgeBaseConstants.TRUNCATION_MARKER;

/**
 * Sanitizes Knowledge Base content before it is handed to the language model.
 *
 * <p>Article bodies come from an external system and are not trusted: they carry raw HTML
 * (which inflates token usage roughly 1.5-2x without adding information) and may contain
 * text that mimics this server's own output structure in an attempt to steer the model.
 */
final class ContentSanitizer {

    /**
     * Matches the structural separators emitted by the formatters. Content that reproduces
     * them at the start of a line is indented so it cannot be mistaken for a real section
     * boundary by the model.
     */
    private static final Pattern STRUCTURAL_MARKER = Pattern.compile("(?m)^(\\s*)(---|===|<<<)");

    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{3,}");

    private ContentSanitizer() {
        // Utility class
    }

    /**
     * Strips HTML, neutralizes structural markers and collapses excess whitespace.
     *
     * @return the cleaned text, or an empty string when {@code raw} is null or blank
     */
    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        // Parse as HTML even when the field is plain text: wholeText() decodes entities
        // and drops tags without introducing markup of its own.
        String text = Jsoup.parse(raw, "", Parser.htmlParser()).wholeText();

        text = STRUCTURAL_MARKER.matcher(text).replaceAll("$1 $2");
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");
        // Non-breaking spaces survive entity decoding and would otherwise reach the
        // model as opaque characters.
        text = text.replace('\u00A0', ' ');

        return text.strip();
    }

    /**
     * Cleans every entry of a list, dropping those that end up empty.
     */
    static List<String> clean(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(ContentSanitizer::clean)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Truncates text to {@code maxChars}, appending a marker that tells the model content
     * was omitted rather than letting it silently assume the article ended.
     */
    static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int omitted = text.length() - maxChars;
        return text.substring(0, maxChars)
                + "\n" + TRUNCATION_MARKER + " " + omitted
                + " more characters; open the URL for the full article]";
    }
}
