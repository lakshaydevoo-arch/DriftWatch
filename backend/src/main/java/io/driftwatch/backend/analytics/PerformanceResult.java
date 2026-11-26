package io.driftwatch.backend.analytics;

import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/performance}: the engine's join report and the engine version that produced it.
 */
public record PerformanceResult(JsonNode join, String engineVersion) {}
