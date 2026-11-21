package io.driftwatch.backend.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client against the <b>real</b> analytics engine: checks that both sides agree on the API
 * contract (field names, multipart parts, problem types).
 *
 * <p>Runs only when {@code DRIFTWATCH_ANALYTICS_URL} points at a running engine, e.g.
 *
 * <pre>
 * (cd analytics &amp;&amp; uvicorn driftwatch_analytics.main:app --port 8000) &amp;
 * DRIFTWATCH_ANALYTICS_URL=http://localhost:8000 ./mvnw verify
 * </pre>
 *
 * The {@code backend} CI workflow has a job that starts the engine and runs this test.
 */
@EnabledIfEnvironmentVariable(named = "DRIFTWATCH_ANALYTICS_URL", matches = ".+")
class AnalyticsEngineContractTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private AnalyticsClient client;

  @BeforeEach
  void connect() {
    client =
        new AnalyticsClient(
            new AnalyticsProperties(
                URI.create(System.getenv("DRIFTWATCH_ANALYTICS_URL")),
                Duration.ofSeconds(5),
                Duration.ofSeconds(60)),
            json);
  }

  /** Seeded CSV: numeric {@code age} (normal), categorical {@code region}. */
  private static ByteArrayResource csv(int rows, long seed, double ageShift, boolean withRegion) {
    Random random = new Random(seed);
    String[] regions = {"north", "south", "east", "west"};
    StringBuilder out = new StringBuilder(withRegion ? "age,region\n" : "age\n");
    for (int i = 0; i < rows; i++) {
      out.append(String.format(Locale.ROOT, "%.4f", 40 + ageShift + 12 * random.nextGaussian()));
      if (withRegion) {
        out.append(',').append(regions[random.nextInt(regions.length)]);
      }
      out.append('\n');
    }
    return new ByteArrayResource(out.toString().getBytes(StandardCharsets.UTF_8));
  }

  private byte[] storedProfile() {
    ProfileResult result = client.profile(csv(2_000, 1, 0, true), "reference.csv", null);
    return json.writeValueAsBytes(result.profile());
  }

  private static JsonNode feature(DriftResult result, String name) {
    for (JsonNode feature : result.body().get("features")) {
      if (feature.get("name").asString().equals(name)) {
        return feature;
      }
    }
    throw new AssertionError("no feature " + name);
  }

  @Test
  void healthReportsTheEngineVersion() {
    EngineHealth health = client.health();

    assertThat(health.status()).isEqualTo("ok");
    assertThat(health.engineVersion()).isNotBlank();
  }

  @Test
  void profileDescribesTheReferenceDataset() {
    ProfileResult result = client.profile(csv(2_000, 1, 0, true), "reference.csv", null);

    JsonNode profile = result.profile();
    assertThat(profile.get("n_rows").asInt()).isEqualTo(2_000);
    assertThat(profile.get("engine_version").asString()).isEqualTo(client.health().engineVersion());
    assertThat(profile.get("features").get("age").get("kind").asString()).isEqualTo("numeric");
    assertThat(profile.get("features").get("region").get("kind").asString())
        .isEqualTo("categorical");
    assertThat(result.issues().isArray()).isTrue();
  }

  @Test
  void profileOptionsArePassedThrough() {
    ProfileResult result =
        client.profile(
            csv(500, 2, 0, true), "reference.csv", json.readTree("{\"exclude\": [\"region\"]}"));

    assertThat(result.profile().get("features").has("region")).isFalse();
    assertThat(result.profile().get("features").has("age")).isTrue();
  }

  @Test
  void storedProfileRoundTripsThroughDrift() {
    byte[] profile = storedProfile();

    DriftResult unchanged = client.drift(profile, csv(1_000, 3, 0, true), "same.csv", null);
    DriftResult shifted = client.drift(profile, csv(1_000, 4, 12, true), "shifted.csv", null);

    assertThat(unchanged.body().get("summary").get("n_drifted").asInt()).isZero();
    assertThat(feature(shifted, "age").get("drift_detected").asBoolean()).isTrue();
    assertThat(feature(shifted, "region").get("drift_detected").asBoolean()).isFalse();
  }

  @Test
  void driftConfigIsPassedThrough() {
    DriftResult result =
        client.drift(
            storedProfile(),
            csv(1_000, 5, 0, true),
            "c.csv",
            json.readTree("{\"min_valid_count\": 5000}"));

    assertThat(feature(result, "age").get("status").asString()).isEqualTo("insufficient_data");
    assertThat(result.body().get("config").get("min_valid_count").asInt()).isEqualTo(5_000);
  }

  @Test
  void malformedCsvIsRejectedWithTheEngineProblemType() {
    ByteArrayResource duplicateHeader =
        new ByteArrayResource("a,a\n1,2\n".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> client.profile(duplicateHeader, "bad.csv", null))
        .isInstanceOfSatisfying(
            AnalyticsRejectedException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(422);
              assertThat(e.getKind()).isEqualTo("invalid-csv");
              assertThat(e.getDetail()).contains("duplicate column names");
            });
  }

  @Test
  void batchWithoutAMonitoredColumnIsASchemaMismatch() {
    byte[] profile = storedProfile();

    assertThatThrownBy(() -> client.drift(profile, csv(500, 6, 0, false), "c.csv", null))
        .isInstanceOfSatisfying(
            AnalyticsRejectedException.class,
            e -> {
              assertThat(e.getKind()).isEqualTo("schema-mismatch");
              assertThat(e.getErrors())
                  .anySatisfy(error ->
                      assertThat(error)
                          .containsEntry("code", "missing_column")
                          .containsEntry("column", "region"));
            });
  }

  @Test
  void corruptedProfileIsRejected() {
    assertThatThrownBy(() ->
            client.drift(
                "{\"format_version\": 1}".getBytes(StandardCharsets.UTF_8),
                csv(100, 7, 0, true),
                "c.csv",
                null))
        .isInstanceOfSatisfying(
            AnalyticsRejectedException.class,
            e -> assertThat(e.getKind()).isEqualTo("invalid-profile"));
  }

  @Test
  void invalidOptionsAreRejected() {
    assertThatThrownBy(() ->
            client.profile(
                csv(100, 8, 0, true),
                "r.csv",
                json.readTree("{\"overrides\": {\"nope\": \"numeric\"}}")))
        .isInstanceOfSatisfying(
            AnalyticsRejectedException.class,
            e -> assertThat(e.getKind()).isEqualTo("invalid-options"));
  }
}
