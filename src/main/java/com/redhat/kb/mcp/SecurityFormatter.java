package com.redhat.kb.mcp;

import java.util.ArrayList;
import java.util.List;

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
                    text(release, "product_name"),
                    text(release, "advisory"),
                    text(release, "package")));
        }

        List<CveDetail.PackageState> unfixed = new ArrayList<>();
        for (JsonNode state : cve.path("package_state")) {
            if (unfixed.size() >= MAX_ENTRIES) {
                break;
            }
            unfixed.add(new CveDetail.PackageState(
                    text(state, "product_name"),
                    text(state, "fix_state"),
                    text(state, "package_name")));
        }

        return new CveDetail(
                text(cve, "name"),
                text(cve, "threat_severity"),
                cve.path("cvss3").path("cvss3_base_score").asText(""),
                ContentSanitizer.clean(joinArray(cve.path("details"))),
                fixed,
                unfixed,
                ContentSanitizer.clean(joinArray(cve.path("mitigation").path("value"))),
                "https://access.redhat.com/security/cve/" + text(cve, "name").toLowerCase());
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

        if (!cve.description().isEmpty()) {
            sb.append("\n").append(cve.description()).append('\n');
        }

        if (!cve.fixedReleases().isEmpty()) {
            sb.append("\n--- Fixed in ---\n");
            for (CveDetail.AffectedRelease r : cve.fixedReleases()) {
                sb.append("  ").append(r.product());
                if (!r.advisory().isEmpty()) {
                    sb.append(" -> ").append(r.advisory());
                }
                sb.append('\n');
            }
        }

        if (!cve.unfixedProducts().isEmpty()) {
            sb.append("\n--- Not yet fixed ---\n");
            for (CveDetail.PackageState s : cve.unfixedProducts()) {
                sb.append("  ").append(s.product()).append(": ").append(s.state()).append('\n');
            }
        }

        if (!cve.mitigation().isEmpty()) {
            sb.append("\n--- Mitigation ---\n").append(cve.mitigation()).append('\n');
        }

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
                    text(version, "name"),
                    text(version, "type"),
                    phaseEnd(version, "General availability"),
                    phaseEnd(version, "Full support"),
                    phaseEnd(version, "Maintenance support")));
        }
        return new LifecycleDetail(text(product, "name"), versions);
    }

    static String formatLifecycle(LifecycleDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(detail.product()).append(" - support life cycle ===\n\n");

        if (detail.versions().isEmpty()) {
            sb.append("No version information published.\n");
            return sb.toString();
        }

        sb.append(String.format("%-10s %-24s %-12s %s%n", "VERSION", "PHASE", "GA", "END OF MAINTENANCE"));
        for (LifecycleDetail.Version v : detail.versions()) {
            sb.append(String.format("%-10s %-24s %-12s %s%n",
                    v.version(),
                    truncate(v.supportType(), 24),
                    date(v.generalAvailability()),
                    date(v.endOfMaintenance())));
        }
        sb.append("\nEnd of maintenance is the practical end of support for a version.\n");
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
