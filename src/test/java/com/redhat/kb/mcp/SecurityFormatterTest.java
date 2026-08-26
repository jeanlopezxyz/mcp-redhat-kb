package com.redhat.kb.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the mapping of Red Hat's public API records into the payloads the model sees.
 *
 * <p>The fixtures mirror the shape of live responses, including the parts that decide an
 * answer: whether a product has a released fix, and where a version sits in its life cycle.
 */
class SecurityFormatterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------- CVE

    private static final String CVE_JSON = """
            {
              "name": "CVE-2024-6387",
              "threat_severity": "Important",
              "cvss3": {"cvss3_base_score": "8.1"},
              "details": ["A signal handler race condition in OpenSSH."],
              "mitigation": {"value": "Set LoginGraceTime to 0."},
              "affected_release": [
                {"product_name": "Red Hat Enterprise Linux 9",
                 "advisory": "RHSA-2024:4312",
                 "package": "openssh-8.7p1-38.el9_4.1"}
              ],
              "package_state": [
                {"product_name": "Red Hat Enterprise Linux 7",
                 "fix_state": "Will not fix",
                 "package_name": "openssh"}
              ]
            }
            """;

    @Test
    @DisplayName("keeps the fields that answer whether a CVE matters")
    void mapsCveEssentials() throws Exception {
        CveDetail cve = SecurityFormatter.toCveDetail(mapper.readTree(CVE_JSON));

        assertEquals("CVE-2024-6387", cve.id());
        assertEquals("Important", cve.severity());
        assertEquals("8.1", cve.cvss3Score());
        assertTrue(cve.description().contains("OpenSSH"));
        assertTrue(cve.mitigation().contains("LoginGraceTime"));
    }

    @Test
    @DisplayName("separates released fixes from products still exposed")
    void separatesFixedFromUnfixed() throws Exception {
        // The distinction is the point of the lookup: "patch available" and "Red Hat will
        // not fix this" call for opposite actions.
        CveDetail cve = SecurityFormatter.toCveDetail(mapper.readTree(CVE_JSON));

        assertEquals(1, cve.fixedReleases().size());
        assertEquals("RHSA-2024:4312", cve.fixedReleases().get(0).advisory());

        assertEquals(1, cve.unfixedProducts().size());
        assertEquals("Will not fix", cve.unfixedProducts().get(0).state());
    }

    @Test
    @DisplayName("renders a CVE with both sections labelled")
    void rendersCve() throws Exception {
        String text = SecurityFormatter.formatCve(SecurityFormatter.toCveDetail(mapper.readTree(CVE_JSON)));

        assertTrue(text.contains("Severity: Important"));
        assertTrue(text.contains("CVSS v3: 8.1"));
        assertTrue(text.contains("--- Fixed in ---"));
        assertTrue(text.contains("--- Not yet fixed ---"));
        assertTrue(text.contains("RHSA-2024:4312"));
    }

    @Test
    @DisplayName("says so plainly when no Red Hat product is affected")
    void rendersUnaffectedCve() throws Exception {
        String json = """
                {"name":"CVE-2024-0001","threat_severity":"Low","details":["Not applicable."]}
                """;

        String text = SecurityFormatter.formatCve(SecurityFormatter.toCveDetail(mapper.readTree(json)));

        assertTrue(text.contains("No Red Hat product is listed as affected"));
    }

    @Test
    @DisplayName("tolerates a record missing the optional blocks")
    void toleratesSparseCve() throws Exception {
        CveDetail cve = SecurityFormatter.toCveDetail(mapper.readTree("{\"name\":\"CVE-2024-0002\"}"));

        assertEquals("CVE-2024-0002", cve.id());
        assertTrue(cve.fixedReleases().isEmpty());
        assertTrue(cve.unfixedProducts().isEmpty());
        assertEquals("", cve.mitigation());
    }

    // ---------------------------------------------------------- Life cycle

    private static final String PRODUCT_JSON = """
            {
              "name": "Red Hat OpenShift Container Platform",
              "versions": [
                {"name": "4.14", "type": "Maintenance Support",
                 "phases": [
                   {"name": "General availability", "end_date": "2023-10-31T00:00:00.000Z"},
                   {"name": "Full support", "end_date": "2024-05-27T00:00:00.000Z"},
                   {"name": "Maintenance support", "end_date": "2025-11-01T00:00:00.000Z"}
                 ]},
                {"name": "4.12", "type": "Extended Update Support",
                 "phases": [
                   {"name": "General availability", "end_date": "2023-01-17T00:00:00.000Z"},
                   {"name": "Maintenance support", "end_date": "N/A"}
                 ]}
              ]
            }
            """;

    @Test
    @DisplayName("maps each version to its phase and support dates")
    void mapsLifecycle() throws Exception {
        LifecycleDetail detail = SecurityFormatter.toLifecycleDetail(mapper.readTree(PRODUCT_JSON));

        assertEquals("Red Hat OpenShift Container Platform", detail.product());
        assertEquals(2, detail.versions().size());

        LifecycleDetail.Version v414 = detail.versions().get(0);
        assertEquals("4.14", v414.version());
        assertEquals("Maintenance Support", v414.supportType());
        assertEquals("2025-11-01T00:00:00.000Z", v414.endOfMaintenance());
    }

    @Test
    @DisplayName("renders dates as calendar days, not timestamps")
    void rendersDatesReadably() throws Exception {
        // The API always reports midnight UTC; the time adds noise and tokens.
        String text = SecurityFormatter.formatLifecycle(
                SecurityFormatter.toLifecycleDetail(mapper.readTree(PRODUCT_JSON)));

        assertTrue(text.contains("2025-11-01"));
        assertFalse(text.contains("T00:00:00.000Z"));
    }

    @Test
    @DisplayName("carries the missing-date marker through unchanged")
    void keepsNotApplicableMarker() throws Exception {
        String text = SecurityFormatter.formatLifecycle(
                SecurityFormatter.toLifecycleDetail(mapper.readTree(PRODUCT_JSON)));

        assertTrue(text.contains("4.12"));
        assertTrue(text.contains("N/A"));
    }

    @Test
    @DisplayName("says so when a product publishes no versions")
    void rendersEmptyLifecycle() throws Exception {
        String text = SecurityFormatter.formatLifecycle(
                SecurityFormatter.toLifecycleDetail(mapper.readTree("{\"name\":\"Some Product\"}")));

        assertTrue(text.contains("No version information published"));
    }
}
