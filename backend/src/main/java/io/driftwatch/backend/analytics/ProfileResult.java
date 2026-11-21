package io.driftwatch.backend.analytics;

import tools.jackson.databind.JsonNode;

/** {@code POST /v1/profile}: the reference profile (stored as is) and validation issues. */
public record ProfileResult(JsonNode profile, JsonNode issues) {}
