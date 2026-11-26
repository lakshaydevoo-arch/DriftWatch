package io.driftwatch.backend.run;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Per-feature results of a successful run.
 *
 * @param features the engine's {@code FeatureDriftResult} objects, in schema order and exactly as
 *     the engine returned them (field names in the engine's snake_case, like {@code summary}); see
 *     {@code analytics/README.md}
 * @param totalFeatures features in the run, before {@code drifted} filtering
 */
public record FeatureResultsResponse(
    UUID runId, int totalFeatures, int returnedFeatures, JsonNode features) {}
