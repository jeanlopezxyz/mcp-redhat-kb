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
     * Matches the structural separators emitted by the formatters at any position of a
     * line, not just its start: entity-encoded HTML (&amp;lt;&amp;lt;&amp;lt;) only becomes
     * a literal marker after Jsoup decodes it, and by then it can sit mid-line where a
     * line-anchored pattern never looks. The run is broken from the inside ("= ==") so it
     * stops parsing as a marker while the surrounding text stays legible.
     */
    private static final Pattern STRUCTURAL_MARKER = Pattern.compile("(---|===|<<<)");

    /**
     * Matches a reproduced fence label wherever it appears. Displacing it is not enough —
     * mid-line it would still read as a fence — so the bracket run is split apart, which
     * leaves the text legible but no longer parseable as a marker. The nonce in
     * {@link UntrustedFence} is the real guarantee; this is defense in depth.
     */
    private static final Pattern FENCE_LABEL =
            Pattern.compile("<<<\\s*((?:END_)?UNTRUSTED_KB_CONTENT)");

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

        // Fence labels first: once split they no longer contain "<<<", so the generic
        // marker rule does not touch them a second time.
        text = FENCE_LABEL.matcher(text).replaceAll("<< < $1");
        // Split the run itself rather than pushing it right with a space: a leading space
        // is undone by the strip() below, which would let a marker that opens the text
        // survive intact — exactly the position where it reads as one of our separators.
        text = STRUCTURAL_MARKER.matcher(text).replaceAll(m -> {
            String marker = m.group(1);
            return marker.charAt(0) + " " + marker.substring(1);
        });
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
