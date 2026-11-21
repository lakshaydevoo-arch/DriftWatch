package io.driftwatch.backend.reference;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftwatch.backend.Multipart;
import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
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
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reference registration end to end: HTTP upload to the backend, real file store, real PostgreSQL
 * and the <b>real</b> analytics engine. Runs when {@code DRIFTWATCH_ANALYTICS_URL} is set (the
 * {@code engine-contract} CI workflow sets it).
 */
@EnabledIfEnvironmentVariable(named = "DRIFTWATCH_ANALYTICS_URL", matches = ".+")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReferenceRegistrationEngineContractTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;

  private UUID modelId;

  @DynamicPropertySource
  static void engine(DynamicPropertyRegistry registry) {
    registry.add("driftwatch.analytics.base-url", () -> System.getenv("DRIFTWATCH_ANALYTICS_URL"));
  }

  @BeforeEach
  void setUp() {
    TestDatabase.clear(jdbc);
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  /** Seeded data: numeric age, categorical region, an identifier column. */
  private static byte[] csv() {
    Random random = new Random(11);
    String[] regions = {"north", "south", "east", "west"};
    StringBuilder out = new StringBuilder("age,region,customer_id\n");
    for (int i = 0; i < 500; i++) {
      out.append(String.format(Locale.ROOT, "%.3f", 40 + 12 * random.nextGaussian()))
          .append(',')
          .append(regions[random.nextInt(regions.length)])
          .append(",c")
          .append(i)
          .append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private HttpResponse<String> upload(Multipart body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/models/" + modelId + "/reference"))
            .header("Content-Type", body.contentType())
            .POST(body.publisher())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode column(JsonNode reference, String name) {
    for (JsonNode column : reference.get("columns")) {
      if (column.get("name").asString().equals(name)) {
        return column;
      }
    }
    throw new AssertionError("no column " + name);
  }

  @Test
  void registersAndInfersTheSchema() throws Exception {
    HttpResponse<String> response =
        upload(new Multipart().file("file", "ref.csv", "text/csv", csv()));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    JsonNode reference = json.readTree(response.body());
    assertThat(reference.get("rowCount").asLong()).isEqualTo(500);
    assertThat(column(reference, "age").get("logicalType").asString()).isEqualTo("numeric");
    assertThat(column(reference, "region").get("logicalType").asString()).isEqualTo("categorical");
    JsonNode id = column(reference, "customer_id");
    assertThat(id.get("monitored").asBoolean()).isFalse();
    assertThat(id.get("excludedReason").asString()).isEqualTo("likely_identifier");

    HttpResponse<String> current =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + port + "/api/v1/models/" + modelId + "/reference"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(json.readTree(current.body())).isEqualTo(reference);
    assertThat(
            jdbc.queryForObject(
                "SELECT profile->'features'->'age'->>'kind' FROM reference_profile", String.class))
        .isEqualTo("numeric");
  }

  @Test
  void optionsReachTheEngine() throws Exception {
    JsonNode reference =
        json.readTree(
            upload(
                    new Multipart()
                        .file("file", "ref.csv", "text/csv", csv())
                        .field("options", "{\"exclude\": [\"region\"]}"))
                .body());

    assertThat(column(reference, "region").get("excludedReason").asString())
        .isEqualTo("user_excluded");
  }

  @Test
  void engineRejectionReachesTheClient() throws Exception {
    HttpResponse<String> response =
        upload(
            new Multipart()
                .file(
                    "file", "bad.csv", "text/csv", "a,a\n1,2\n".getBytes(StandardCharsets.UTF_8)));

    assertThat(response.statusCode()).isEqualTo(422);
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asString()).isEqualTo("urn:driftwatch:problem:invalid-csv");
    assertThat(problem.get("detail").asString()).contains("duplicate column names");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM reference_profile", Integer.class))
        .isZero();
  }
}
