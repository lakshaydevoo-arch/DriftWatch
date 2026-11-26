package io.driftwatch.backend.run;

import static io.driftwatch.backend.run.RunFixtures.awaitFinished;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
 * Monitoring runs against the <b>real</b> analytics engine: the stored profile goes back to the
 * engine unchanged and its real response is accepted and stored. Runs when {@code
 * DRIFTWATCH_ANALYTICS_URL} is set (the {@code engine-contract} CI workflow sets it).
 */
@EnabledIfEnvironmentVariable(named = "DRIFTWATCH_ANALYTICS_URL", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunEngineContractTest {

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
  void setUp() throws Exception {
    TestDatabase.clear(jdbc);
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
    references.register(modelId, "ref.csv", new ByteArrayInputStream(csv(1, 1000, 40)), null);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  /** Seeded numeric {@code age} with the given mean, and a categorical {@code region}. */
  private static byte[] csv(long seed, int rows, double meanAge) {
    Random random = new Random(seed);
    String[] regions = {"north", "south", "east", "west"};
    StringBuilder out = new StringBuilder("age,region\n");
    for (int i = 0; i < rows; i++) {
      out.append(String.format(Locale.ROOT, "%.3f", meanAge + 12 * random.nextGaussian()))
          .append(',')
          .append(regions[random.nextInt(regions.length)])
          .append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private MonitoringRun run(byte[] current) throws Exception {
    MonitoringRun run = runs.submit(modelId, "today.csv", new ByteArrayInputStream(current), null);
    return awaitFinished(runs, run.getId());
  }

  private JsonNode feature(JsonNode result, String name) {
    for (JsonNode feature : result.get("features")) {
      if (feature.get("name").asString().equals(name)) {
        return feature;
      }
    }
    throw new AssertionError("no feature " + name);
  }

  @Test
  void aShiftedFeatureIsDetectedAndTheResultStored() throws Exception {
    MonitoringRun run = run(csv(2, 1000, 55));

    assertThat(run.getStatus()).as(run.getErrorMessage()).isEqualTo(RunStatus.SUCCEEDED);
    JsonNode result = json.readTree(run.getResult());
    assertThat(result.get("n_rows").asLong()).isEqualTo(1000);
    assertThat(feature(result, "age").get("drift_detected").asBoolean()).isTrue();
    assertThat(feature(result, "region").get("drift_detected").asBoolean()).isFalse();
    assertThat(run.getEngineVersion()).isEqualTo(result.get("engine_version").asString());
  }

  @Test
  void aBatchWithoutAMonitoredColumnFailsWithSchemaMismatch() throws Exception {
    MonitoringRun run = run("region\nnorth\nsouth\n".getBytes(StandardCharsets.UTF_8));

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("schema-mismatch");
    assertThat(run.getErrorDetails()).contains("age");
  }
}
