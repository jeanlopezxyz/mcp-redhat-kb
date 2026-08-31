package com.redhat.kb.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured payload for a product life cycle lookup.
 *
 * <p>Each version carries up to seven support phases in the API response. Only the current
 * phase and the dates that bound support are kept: a product with two dozen versions would
 * otherwise render as several thousand tokens of dates nobody asked about.
 */
public record LifecycleDetail(
        @JsonPropertyDescription("Product name as Red Hat publishes it") String product,
        @JsonPropertyDescription("Released versions, most recent first") List<Version> versions) {

    public record Version(
            @JsonPropertyDescription("Version number, e.g. 4.14") String version,
            @JsonPropertyDescription("Current support type, e.g. Full Support, Maintenance Support, Retired")
            String supportType,
            @JsonPropertyDescription("General availability date, ISO-8601, or 'N/A'") String generalAvailability,
            @JsonPropertyDescription("End of full support, ISO-8601, or 'N/A'") String endOfFullSupport,
            @JsonPropertyDescription("End of maintenance support: the practical end of life, or 'N/A'")
            String endOfMaintenance) {
    }
}
