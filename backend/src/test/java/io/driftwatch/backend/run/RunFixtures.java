package io.driftwatch.backend.run;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.ProfileResult;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Shared data and helpers for the run tests (engine replaced by a mock). */
final class RunFixtures {

  static final byte[] REFERENCE_CSV = "age\n31\n45\n52\n".getBytes(StandardCharsets.UTF_8);
  static final byte[] CURRENT_CSV = "age\n60\n61\n".getBytes(StandardCharsets.UTF_8);

  /** Shaped like the engine's profile (the fields the backend reads). */
  static String profile(String marker) {
    return """
    {"format_version": 1, "engine_version": "0.1.0.dev0", "n_rows": 3, "marker": "%s",
     "dataset_schema": {"columns": [
       {"name": "age", "logical_type": "numeric", "source": "inferred",
        "excluded": null, "integer_coded": false}]},
     "features": {"age": {"kind": "numeric"}}, "issues": []}
    """
        .formatted(marker);
  }

  /** Shaped like the engine's {@code DriftResponse}. */
  static final String DRIFT_RESPONSE =
      """
      {"engine_version": "0.1.0.dev0", "profile_engine_version": "0.1.0.dev0",
       "config": {"numeric": {"psi_threshold": 0.2}}, "n_rows": 2,
       "summary": {"n_features": 1, "n_evaluated": 1, "n_insufficient_data": 0,
                   "n_drifted": 1, "n_with_quality_flags": 0, "drift_share": 1.0,
                   "dataset_drift": true},
       "features": [{"name": "age", "drift_detected": true}],
       "issues": [], "warnings": []}
      """;

  private RunFixtures() {}

  /** Registers a reference through the real service, with the engine mock returning a profile. */
  static UUID registerReference(
      ReferenceService references, AnalyticsClient analytics, UUID modelId, String marker)
      throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    doAnswer(invocation -> new ProfileResult(json.readTree(profile(marker)), json.readTree("[]")))
        .when(analytics)
        .profile(any(), anyString(), any());
    return references
        .register(modelId, "reference.csv", new ByteArrayInputStream(REFERENCE_CSV), null)
        .reference()
        .getId();
  }

  /** Waits until the run has finished, then returns it. */
  static MonitoringRun awaitFinished(RunService runs, UUID runId) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(20))
        .until(() -> runs.get(runId).getStatus().isTerminal());
    return runs.get(runId);
  }
}
