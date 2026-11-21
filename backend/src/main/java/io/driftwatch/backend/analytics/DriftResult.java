package io.driftwatch.backend.analytics;

import tools.jackson.databind.JsonNode;

/** {@code POST /v1/drift}: the engine's full response (summary, features, issues, config). */
public record DriftResult(JsonNode body) {}
