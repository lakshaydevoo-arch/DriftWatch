package io.driftwatch.backend.analytics;

import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/performance}: the join report, the metrics on the joined rows, the configuration
 * the engine applied, and the engine version.
 */
public record PerformanceResult(
    JsonNode join, JsonNode metrics, JsonNode config, String engineVersion) {}
