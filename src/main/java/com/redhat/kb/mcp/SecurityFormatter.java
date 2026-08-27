package com.redhat.kb.mcp;

import com.redhat.kb.mcp.model.CveDetail;
import com.redhat.kb.mcp.model.LifecycleDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Renders Security Data and Product Life Cycle records for the model.
 *
 * <p>Both APIs return far more than a question needs: a CVE record carries references and
 * acknowledgements, and a product carries seven phases per version. These mappings keep the
 * fields that decide an answer and drop the rest.
 */
final class SecurityFormatter {

    /** Cap on how many entries of a list are rendered, to bound a widely-affecting CVE. */
    private static final int MAX_ENTRIES = 25;

    /** Mirrors the validation in SecurityDataClient; the id in the body is not trusted. */
    private static final Pattern CVE_ID = Pattern.compile("CVE-\\d{4}-\\d{4,19}", Pattern.CASE_INSENSITIVE);

    private SecurityFormatter() {
        // Utility class
    }

    // ---------------------------------------------------------------- CVE

    static CveDetail toCveDetail(JsonNode cve) {
        List<CveDetail.AffectedRelease> fixed = new ArrayList<>();
        for (JsonNode release : cve.path("affected_release")) {
            if (fixed.size() >= MAX_ENTRIES) {
                break;
            }
            fixed.add(new CveDetail.AffectedRelease(
                    ContentSanitizer.clean(text(release, "product_name")),
                    ContentSanitizer.clean(text(release, "advisory")),
                    ContentSanitizer.clean(text(release, "package"))));
        }

        List<CveDetail.PackageState> unfixed = new ArrayList<>();
        for (JsonNode state : cve.path("package_state")) {
            if (unfixed.size() >= MAX_ENTRIES) {
                break;
            }
            unfixed.add(new CveDetail.PackageState(
                    ContentSanitizer.clean(text(state, "product_name")),
                    ContentSanitizer.clean(text(state, "fix_state")),
                    ContentSanitizer.clean(text(state, "package_name"))));
        }

        // The id, severity and score render outside the fence, as the server's own header,
        // so they are sanitized too: a tampered record must not be able to inject markers
        // into the one part of the answer the model reads as trusted.
        return new CveDetail(
                ContentSanitizer.clean(text(cve, "name")),
                ContentSanitizer.clean(text(cve, "threat_severity")),
                ContentSanitizer.clean(cve.path("cvss3").path("cvss3_base_score").asText("")),
                ContentSanitizer.clean(joinArray(cve.path("details"))),
                fixed,
                unfixed,
                ContentSanitizer.clean(joinArray(cve.path("mitigation").path("value"))),
                cveUrl(text(cve, "name")));
    }

    /**
     * Builds the advisory URL only from a well-formed CVE id. The id arrives in the
     * response body, so interpolating it unchecked would let a tampered record point the
     * model at an arbitrary path under the Red Hat domain.
     */
    private static String cveUrl(String name) {
        if (!CVE_ID.matcher(name).matches()) {
            return "";
        }
        return "https://access.redhat.com/security/cve/" + name.toLowerCase(Locale.ROOT);
    }

    static String formatCve(CveDetail cve) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(cve.id()).append(" ===\n\n");
        sb.append("Severity: ").append(orUnknown(cve.severity()));
        if (!cve.cvss3Score().isEmpty()) {
            sb.append("   CVSS v3: ").append(cve.cvss3Score());
        }
        sb.append('\n');
        sb.append("URL: ").append(cve.url()).append("\n");

        // Everything below the header comes from the Security Data API: descriptions and
        // mitigations are free text an upstream compromise could abuse, so they get the
        // same nonce fence as Knowledge Base articles. The section headings render inside
        // the fence because they only label that remote content.
        StringBuilder body = new StringBuilder();
        if (!cve.description().isEmpty()) {
            body.append(cve.description()).append('\n');
        }

        if (!cve.fixedReleases().isEmpty()) {
            body.append("\n--- Fixed in ---\n");
            for (CveDetail.AffectedRelease r : cve.fixedReleases()) {
                body.append("  ").append(r.product());
                if (!r.advisory().isEmpty()) {
                    body.append(" -> ").append(r.advisory());
                }
                body.append('\n');
            }
        }

        if (!cve.unfixedProducts().isEmpty()) {
            body.append("\n--- Not yet fixed ---\n");
            for (CveDetail.PackageState s : cve.unfixedProducts()) {
                body.append("  ").append(s.product()).append(": ").append(s.state()).append('\n');
            }
        }

        if (!cve.mitigation().isEmpty()) {
            body.append("\n--- Mitigation ---\n").append(cve.mitigation()).append('\n');
        }

        if (!body.isEmpty()) {
            UntrustedFence fence = UntrustedFence.newFence();
            sb.append('\n').append(fence.open()).append('\n');
            sb.append(body);
            sb.append(fence.close()).append('\n');
        }

        // Our own conclusion, not upstream data: it must stay outside the fence or the
        // model treats it as content it was told never to act on.
        if (cve.fixedReleases().isEmpty() && cve.unfixedProducts().isEmpty()) {
            sb.append("\nNo Red Hat product is listed as affected.\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------- Life cycle

    static LifecycleDetail toLifecycleDetail(JsonNode product) {
        List<LifecycleDetail.Version> versions = new ArrayList<>();
        for (JsonNode version : product.path("versions")) {
            versions.add(new LifecycleDetail.Version(
                    ContentSanitizer.clean(text(version, "name")),
                    ContentSanitizer.clean(text(version, "type")),
                    phaseEnd(version, "General availability"),
                    phaseEnd(version, "Full support"),
                    phaseEnd(version, "Maintenance support")));
        }
        return new LifecycleDetail(
                ContentSanitizer.clean(text(product, "name")), versions);
    }

    static String formatLifecycle(LifecycleDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(detail.product()).append(" - support life cycle ===\n\n");

        if (detail.versions().isEmpty()) {
            sb.append("No version information published.\n");
            return sb.toString();
        }

        // Product and version names are free text from the Life Cycle API, so the table
        // gets the same nonce fence as CVEs and articles. The heading labels are ours.
        StringBuilder body = new StringBuilder();
        body.append(String.format("%-10s %-24s %-12s %s%n", "VERSION", "PHASE", "GA", "END OF MAINTENANCE"));
        for (LifecycleDetail.Version v : detail.versions()) {
            body.append(String.format("%-10s %-24s %-12s %s%n",
                    v.version(),
                    truncate(v.supportType(), 24),
                    date(v.generalAvailability()),
                    date(v.endOfMaintenance())));
        }

        UntrustedFence fence = UntrustedFence.newFence();
        sb.append(fence.open()).append('\n');
        sb.append(body);
        sb.append(fence.close()).append('\n');

        // Our own conclusion, not upstream data: it stays outside the fence.
        sb.append("\nEnd of maintenance is the practical end of support for a version.\n");
        // Red Hat publishes only versions still in a support phase, so a version's absence
        // is the answer to "is it still supported?" — say so, or a model asked about
        // RHEL 7 sees a table without it and cannot tell "out of support" from "no data".
        sb.append("Only versions still under a support phase are listed; one absent from "
                + "this table has reached end of maintenance.\n");
        return sb.toString();
    }

    /** Reads the end date of a named phase; the API spells missing dates as "N/A". */
    private static String phaseEnd(JsonNode version, String phaseName) {
        for (JsonNode phase : version.path("phases")) {
            if (phaseName.equalsIgnoreCase(phase.path("name").asText(""))) {
                return phase.path("end_date").asText("N/A");
            }
        }
        return "N/A";
    }

    /** Keeps just the calendar day: the time component is always midnight UTC. */
    private static String date(String isoDate) {
        if (isoDate == null || isoDate.isBlank() || "N/A".equals(isoDate)) {
            return "N/A";
        }
        return isoDate.length() >= 10 ? isoDate.substring(0, 10) : isoDate;
    }

    private static String joinArray(JsonNode node) {
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(n -> parts.add(n.asText("")));
            return String.join("\n", parts);
        }
        return node.asText("");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
