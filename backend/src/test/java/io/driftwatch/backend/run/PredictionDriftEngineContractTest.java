package io.driftwatch.backend.run;

import static io.driftwatch.backend.run.RunFixtures.awaitFinished;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceResponse;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A model with prediction and target columns against the <b>real</b> engine: the backend sends the
 * model's columns, the prediction is monitored separately, and production batches without labels
 * are accepted. Runs when {@code DRIFTWATCH_ANALYTICS_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "DRIFTWATCH_ANALYTICS_URL", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PredictionDriftEngineContractTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @Autowired private ReferenceService references;
  @Autowired private RunService runs;

  private UUID modelId;

  @DynamicPropertySource
  static void engine(DynamicPropertyRegistry registry) {
    registry.add("driftwatch.analytics.base-url", () -> System.getenv("DRIFTWATCH_ANALYTICS_URL"));
  }

  @BeforeEach
  void setUp() {
    TestDatabase.clear(jdbc);
    modelId =
        models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, "defaulted", "score").getId();
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  /**
   * Seeded rows: numeric {@code age}, a probability {@code score} (logistic of a normal with the
   * given mean) and, optionally, a 0/1 label {@code defaulted} drawn from it.
   */
  private static byte[] csv(long seed, int rows, double logitMean, boolean withLabel) {
    Random random = new Random(seed);
    StringBuilder out = new StringBuilder(withLabel ? "age,score,defaulted\n" : "age,score\n");
    for (int i = 0; i < rows; i++) {
      double score = 1 / (1 + Math.exp(-(logitMean + random.nextGaussian())));
      out.append(String.format(Locale.ROOT, "%.3f,%.4f", 40 + 12 * random.nextGaussian(), score));
      if (withLabel) {
        out.append(',').append(random.nextDouble() < score ? 1 : 0);
      }
      out.append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private MonitoringRun run(byte[] current) throws Exception {
    return awaitFinished(
        runs, runs.submit(modelId, "today.csv", new ByteArrayInputStream(current), null).getId());
  }

  @Test
  void predictionDriftIsReportedSeparatelyAndLabelsAreNotNeeded() throws Exception {
    ReferenceService.Registered registered =
        references.register(
            modelId, "ref.csv", new ByteArrayInputStream(csv(1, 2000, -1, true)), null);
    Map<String, ReferenceResponse.ColumnView> columns =
        ReferenceResponse.of(registered.reference(), registered.dataset(), json).columns().stream()
            .collect(Collectors.toMap(ReferenceResponse.ColumnView::name, c -> c));
    assertThat(columns.get("score").role()).isEqualTo("prediction");
    assertThat(columns.get("score").logicalType()).isEqualTo("numeric");
    assertThat(columns.get("score").monitored()).isTrue();
    assertThat(columns.get("defaulted").role()).isEqualTo("target");
    assertThat(columns.get("defaulted").excludedReason()).isEqualTo("target_column");

    // Same inputs, predictions shifted upwards, no label column (it arrives later).
    MonitoringRun run = run(csv(2, 1000, 0.5, false));

    assertThat(run.getStatus()).as(run.getErrorMessage()).isEqualTo(RunStatus.SUCCEEDED);
    JsonNode result = json.readTree(run.getResult());
    JsonNode summary = result.get("summary");
    assertThat(summary.get("prediction_drift").asBoolean()).isTrue();
    assertThat(summary.get("n_features").asInt()).isEqualTo(1);
    assertThat(summary.get("n_drifted").asInt()).isZero();
    assertThat(result.get("issues").isEmpty()).isTrue();
  }

  @Test
  void unchangedPredictionsHaveNoPredictionDrift() throws Exception {
    references.register(modelId, "ref.csv", new ByteArrayInputStream(csv(3, 2000, -1, true)), null);

    MonitoringRun run = run(csv(4, 1000, -1, false));

    assertThat(run.getStatus()).as(run.getErrorMessage()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(json.readTree(run.getResult()).get("summary").get("prediction_drift").asBoolean())
        .isFalse();
  }

  @Test
  void aBatchWithoutPredictionsFailsWithSchemaMismatch() throws Exception {
    references.register(modelId, "ref.csv", new ByteArrayInputStream(csv(5, 500, -1, true)), null);

    MonitoringRun run = run("age\n41\n42\n".getBytes(StandardCharsets.UTF_8));

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("schema-mismatch");
    assertThat(run.getErrorDetails()).contains("score");
  }
}
