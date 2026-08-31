package com.redhat.kb.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of an audit line.
 *
 * <p>The OWASP table claims this log records the tool, subject, address and fingerprint and
 * never a token. What keeps the second half of that promise is the preview: the argument is
 * the one field carrying caller-supplied text, so it is the only way something secret could
 * reach the log by accident. It is exercised by reflection because it is private, and it is
 * worth pinning anyway — the guarantee is the reason the field is truncated at all.
 */
class ToolAuditLogTest {

    /** Mirrors QUERY_PREVIEW_CHARS in the class under test. */
    private static final int PREVIEW_CHARS = 60;

    @Test
    @DisplayName("keeps a short argument verbatim")
    void keepsShortArgumentVerbatim() {
        assertEquals("CrashLoopBackOff", preview("CrashLoopBackOff"));
    }

    @Test
    @DisplayName("truncates an argument that would otherwise flood the log")
    void truncatesLongArgument() {
        String preview = preview("x".repeat(500));

        assertEquals(PREVIEW_CHARS + 3, preview.length(), "expected the cap plus an ellipsis");
        assertTrue(preview.endsWith("..."), "a truncated preview should say so: " + preview);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("renders an absent argument as an empty field")
    void rendersAbsentArgumentAsEmpty(String argument) {
        assertEquals("", preview(argument));
    }

    @Test
    @DisplayName("strips the quotes and newlines that would break up an audit line")
    void stripsLineBreakingCharacters() {
        String preview = preview("tool=\"other\"\nsubject=admin\r\nquery");

        assertFalse(preview.contains("\""), "a quote would close the arg field early: " + preview);
        assertFalse(preview.contains("\n"), "a newline would forge a second entry: " + preview);
        assertFalse(preview.contains("\r"), "a carriage return would forge a second entry: " + preview);
    }

    @Test
    @DisplayName("truncates before an overlong argument can carry a secret into the log")
    void truncatesBeforeASecretCouldSurvive() {
        String secret = "sha256-" + "a".repeat(80);

        assertFalse(preview("query " + secret).contains(secret),
                "the audit trail must never carry token material in full");
    }

    private static String preview(String argument) {
        try {
            Method method = ToolAuditLog.class.getDeclaredMethod("preview", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, argument);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("preview(String) should exist on ToolAuditLog", e);
        }
    }
}
