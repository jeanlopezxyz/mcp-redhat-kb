package com.redhat.kb;

/**
 * Constants for Red Hat Knowledge Base MCP.
 */
public final class KnowledgeBaseConstants {

    private KnowledgeBaseConstants() {
        // Utility class
    }

    // Error messages
    public static final String ERROR_NOT_CONFIGURED = "Error: REDHAT_TOKEN not configured";

    // Input limits
    public static final int MAX_QUERY_LENGTH = 1000;

    // Result limits
    public static final int DEFAULT_MAX_RESULTS = 10;
    public static final int MIN_RESULTS = 1;
    public static final int MAX_RESULTS = 50;

    // Default values
    public static final String DEFAULT_PRODUCT = "Red Hat OpenShift Container Platform";
}
