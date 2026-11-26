package io.driftwatch.backend.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsRejectedException;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import io.driftwatch.backend.run.RunService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
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
 * Labels joined by the <b>real</b> engine: model with a record id, reference, run, then labels for
 * part of the batch. Runs when {@code DRIFTWATCH_ANALYTICS_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "DRIFTWATCH_ANALYTICS_URL", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LabelsEngineContractTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @Autowired private ReferenceService references;
  @Autowired private RunService runs;
  @Autowired private LabelService labels;

  private UUID modelId;

  @DynamicPropertySource
  static void engine(DynamicPropertyRegistry registry) {
    registry.add("driftwatch.analytics.base-url", () -> System.getenv("DRIFTWATCH_ANALYTICS_URL"));
  }

  @BeforeEach
  void setUp() throws Exception {
    TestDatabase.clear(jdbc);
    modelId =
        models
            .create(
                "credit-risk", TaskType.BINARY_CLASSIFICATION, "defaulted", "score", "application")
            .getId();
    references.register(
        modelId, "ref.csv", new ByteArrayInputStream(rows("r", 1, 500, true)), null);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  /** Seeded rows: id, numeric age, probability score and (optionally) a 0/1 label. */
  private static byte[] rows(String prefix, long seed, int n, boolean withLabel) {
    Random random = new Random(seed);
    StringBuilder out =
        new StringBuilder(
            withLabel ? "application,age,score,defaulted\n" : "application,age,score\n");
    for (int i = 0; i < n; i++) {
      double score = 1 / (1 + Math.exp(-random.nextGaussian()));
      out.append(prefix)
          .append(i)
          .append(String.format(Locale.ROOT, ",%.3f,%.4f", 40 + 12 * random.nextGaussian(), score));
      if (withLabel) {
        out.append(',').append(random.nextDouble() < score ? 1 : 0);
      }
      out.append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private UUID finishedRun() throws Exception {
    UUID run =
        runs.submit(modelId, "today.csv", new ByteArrayInputStream(rows("c", 2, 200, false)), null)
            .getId();
    await().atMost(Duration.ofSeconds(20)).until(() -> runs.get(run).getStatus().isTerminal());
    return run;
  }

  @Test
  void labelsForPartOfTheBatchJoinAndTheJoinRateIsStored() throws Exception {
    UUID run = finishedRun();
    // Labels for 150 of the 200 batch rows, one of them empty, plus 5 unknown ids.
    StringBuilder csv = new StringBuilder("application,defaulted\n");
    for (int i = 0; i < 150; i++) {
      csv.append('c')
          .append(i)
          .append(',')
          .append(i == 7 ? "" : String.valueOf(i % 2))
          .append('\n');
    }
    for (int i = 0; i < 5; i++) {
      csv.append("unknown").append(i).append(",1\n");
    }

    LabelService.Uploaded uploaded =
        labels.upload(
            run,
            "labels.csv",
            new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)),
            null);

    JsonNode join = json.readTree(uploaded.labels().getJoinReport());
    assertThat(join.get("n_current_rows").asInt()).isEqualTo(200);
    assertThat(join.get("n_label_rows").asInt()).isEqualTo(155);
    assertThat(join.get("n_matched").asInt()).isEqualTo(150);
    assertThat(join.get("n_missing_labels").asInt()).isEqualTo(1);
    assertThat(join.get("n_joined").asInt()).isEqualTo(149);
    assertThat(join.get("n_unmatched_labels").asInt()).isEqualTo(5);
    assertThat(join.get("join_rate").asDouble()).isEqualTo(149 / 200.0);
    assertThat(labels.current(run).getId()).isEqualTo(uploaded.labels().getId());

    // Metrics on the 149 joined rows: the batch's scores against these labels.
    JsonNode metrics = json.readTree(uploaded.labels().getMetrics());
    assertThat(metrics.get("status").asString()).isEqualTo("evaluated");
    assertThat(metrics.get("task_type").asString()).isEqualTo("binary_classification");
    assertThat(metrics.get("n_evaluated").asInt()).isEqualTo(149);
    assertThat(metrics.get("positive_label").asString()).isEqualTo("true");
    for (String metric : List.of("accuracy", "precision", "recall", "f1", "roc_auc")) {
      assertThat(metrics.get("metrics").has(metric)).as(metric).isTrue();
    }
    assertThat(json.readTree(uploaded.labels().getConfig()).get("min_evaluated").asInt())
        .isEqualTo(30);
  }

  @Test
  void duplicateIdsAreRejectedByTheEngine() throws Exception {
    UUID run = finishedRun();

    assertThatThrownBy(() ->
            labels.upload(
                run,
                "labels.csv",
                new ByteArrayInputStream(
                    "application,defaulted\nc1,1\nc1,0\n".getBytes(StandardCharsets.UTF_8)),
                null))
        .isInstanceOf(AnalyticsRejectedException.class)
        .extracting(e -> ((AnalyticsRejectedException) e).getKind())
        .isEqualTo("duplicate-record-ids");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM run_labels", Integer.class)).isZero();
  }
}
