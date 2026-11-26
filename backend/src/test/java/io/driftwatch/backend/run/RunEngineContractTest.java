package io.driftwatch.backend.run;

import static io.driftwatch.backend.run.RunFixtures.awaitFinished;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftwatch.backend.Multipart;
import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.web.server.LocalServerPort;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RunEngineContractTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
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

  @Test
  void overHttpARunIsAcceptedAndPolledToItsResult() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    Multipart body =
        new Multipart()
            .file("current", "today.csv", "text/csv", csv(3, 800, 55))
            .field("config", "{\"numeric\": {\"psi_threshold\": 0.25}}");

    HttpResponse<String> accepted =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/models/" + modelId + "/runs"))
                .header("Content-Type", body.contentType())
                .POST(body.publisher())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
    URI location = URI.create(accepted.headers().firstValue("Location").orElseThrow());
    awaitFinished(runs, UUID.fromString(json.readTree(accepted.body()).get("id").asString()));

    JsonNode run =
        json.readTree(
            http.send(
                    HttpRequest.newBuilder(location).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("SUCCEEDED");
    JsonNode result = run.get("result");
    assertThat(result.get("rowCount").asLong()).isEqualTo(800);
    assertThat(result.get("summary").get("n_features").asInt()).isEqualTo(2);
    assertThat(result.get("summary").get("n_drifted").asInt()).isGreaterThanOrEqualTo(1);
    // The engine applied the requested threshold and reports it in the full config.
    assertThat(result.get("config").get("numeric").get("psi_threshold").asDouble()).isEqualTo(0.25);

    // Per-feature results: the real FeatureDriftResult shape, in schema order.
    JsonNode features =
        json.readTree(
            http.send(
                    HttpRequest.newBuilder(URI.create(location + "/features?drifted=true"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    assertThat(features.get("totalFeatures").asInt()).isEqualTo(2);
    JsonNode age = features.get("features").get(0);
    assertThat(age.get("name").asString()).isEqualTo("age");
    assertThat(age.get("drift_detected").asBoolean()).isTrue();
    assertThat(age.get("drift").get("kind").asString()).isEqualTo("numeric");
    assertThat(age.get("drift").get("psi").asDouble())
        .isGreaterThan(age.get("drift").get("effective_threshold").asDouble());

    // Run history contains the run.
    JsonNode history =
        json.readTree(
            http.send(
                    HttpRequest.newBuilder(
                            URI.create(
                                "http://localhost:" + port + "/api/v1/models/" + modelId + "/runs"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    assertThat(history.get("items").get(0)).isEqualTo(run);
  }

  @Test
  void aConfigTheEngineRejectsFailsTheRunWithInvalidConfig() throws Exception {
    MonitoringRun run =
        awaitFinished(
            runs,
            runs.submit(
                    modelId,
                    "today.csv",
                    new ByteArrayInputStream(csv(4, 200, 40)),
                    json.readTree("{\"psi_threshold\": 0.25}"))
                .getId());

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("invalid-config");
    assertThat(run.getErrorDetails()).contains("psi_threshold");
  }
}
