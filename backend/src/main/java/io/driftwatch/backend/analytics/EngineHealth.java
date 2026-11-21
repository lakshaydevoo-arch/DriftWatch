package io.driftwatch.backend.analytics;

/** {@code GET /health} of the analytics engine. */
public record EngineHealth(String status, String engineVersion) {}
