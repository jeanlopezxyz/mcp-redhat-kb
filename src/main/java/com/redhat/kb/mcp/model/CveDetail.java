package com.redhat.kb.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured payload for a CVE lookup.
 *
 * <p>A deliberate subset of the API record: the raw response carries acknowledgements,
 * references and per-package errata that would cost thousands of tokens without helping
 * answer "does this affect me, and is there a fix?".
 */
public record CveDetail(
        @JsonPropertyDescription("CVE identifier, e.g. CVE-2024-6387") String id,
        @JsonPropertyDescription("Red Hat severity rating: Low, Moderate, Important or Critical")
        String severity,
        @JsonPropertyDescription("CVSS v3 base score, 0-10") String cvss3Score,
        @JsonPropertyDescription("Short description of the flaw") String description,
        @JsonPropertyDescription("Released fixes, as product plus the advisory shipping them")
        List<AffectedRelease> fixedReleases,
        @JsonPropertyDescription("Products where the fix state is not 'Fixed', with that state")
        List<PackageState> unfixedProducts,
        @JsonPropertyDescription("Workaround when no fix is available yet") String mitigation,
        @JsonPropertyDescription("Canonical Red Hat page for this CVE") String url) {

    /**
     * An empty record for a CVE Red Hat does not track.
     *
     * <p>A tool that declares an output schema must return structured content on every
     * success, so "not found" carries this rather than text alone.
     */
    public static CveDetail notFound(String cveId) {
        return new CveDetail(cveId, "", "", "", List.of(), List.of(), "", "");
    }

    /** A product that has a released fix. */
    public record AffectedRelease(
            @JsonPropertyDescription("Affected product and version") String product,
            @JsonPropertyDescription("Advisory shipping the fix, e.g. RHSA-2024:4312") String advisory,
            @JsonPropertyDescription("Fixed package version") String packageName) {
    }

    /** A product whose fix state is still open. */
    public record PackageState(
            @JsonPropertyDescription("Affected product and version") String product,
            @JsonPropertyDescription("Fix state: Affected, Will not fix, Under investigation, ...")
            String state,
            @JsonPropertyDescription("Affected package") String packageName) {
    }
}
