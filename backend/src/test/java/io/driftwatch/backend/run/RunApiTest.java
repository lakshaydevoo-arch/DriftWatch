package io.driftwatch.backend.run;

import static io.driftwatch.backend.run.RunFixtures.CURRENT_CSV;
import static io.driftwatch.backend.run.RunFixtures.DRIFT_RESPONSE;
import static io.driftwatch.backend.run.RunFixtures.awaitFinished;
import static io.driftwatch.backend.run.RunFixtures.registerReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.driftwatch.backend.Multipart;
import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsRejectedException;
import io.driftwatch.backend.analytics.DriftResult;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The runs API over HTTP, with the engine mocked. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RunApiTest {

  private static final String PROBLEM = "urn:driftwatch:problem:";

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @Autowired private ReferenceService references;
  @Autowired private RunService runs;
  @MockitoBean private AnalyticsClient analytics;

  private UUID modelId;
  private UUID referenceId;
  private final AtomicReference<JsonNode> configSent = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    TestDatabase.clear(jdbc);
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
    referenceId = registerReference(references, analytics, modelId, "first");
    doAnswer(
            invocation -> {
              configSent.set(invocation.getArgument(3));
              return new DriftResult(json.readTree(DRIFT_RESPONSE));
            })
        .when(analytics)
        .drift(any(), any(), anyString(), any());
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private HttpResponse<String> submit(UUID model, Multipart body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri("/api/v1/models/" + model + "/runs"))
            .header("Content-Type", body.contentType())
            .POST(body.publisher())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> submit(Multipart body) throws Exception {
    return submit(modelId, body);
  }

  private static Multipart batch() {
    return new Multipart().file("current", "today.csv", "text/csv", CURRENT_CSV);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Submits, waits for the run to finish, and returns GET /api/v1/runs/{id}. */
  private JsonNode submitAndFetch(Multipart body) throws Exception {
    HttpResponse<String> accepted = submit(body);
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
    UUID id = UUID.fromString(json.readTree(accepted.body()).get("id").asString());
    awaitFinished(runs, id);
    HttpResponse<String> response = get("/api/v1/runs/" + id);
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }

  private JsonNode problem(HttpResponse<String> response) {
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    return json.readTree(response.body());
  }

  @Test
  void submitAnswers202WithTheRunAndItsLocation() throws Exception {
    HttpResponse<String> response = submit(batch());

    assertThat(response.statusCode()).isEqualTo(202);
    JsonNode run = json.readTree(response.body());
    String id = run.get("id").asString();
    assertThat(response.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + "/api/v1/runs/" + id);
    assertThat(run.get("status").asString()).isEqualTo("PENDING");
    assertThat(run.get("modelId").asString()).isEqualTo(modelId.toString());
    assertThat(run.get("referenceId").asString()).isEqualTo(referenceId.toString());
    JsonNode dataset = run.get("currentDataset");
    assertThat(dataset.get("byteSize").asLong()).isEqualTo(CURRENT_CSV.length);
    assertThat(dataset.get("originalFilename").asString()).isEqualTo("today.csv");
    assertThat(dataset.get("sha256").asString()).hasSize(64);
    assertThat(run.get("result").isNull()).isTrue();
    assertThat(run.get("error").isNull()).isTrue();
    assertThat(run.get("startedAt").isNull()).isTrue();
    awaitFinished(runs, UUID.fromString(id));
  }

  @Test
  void aSucceededRunShowsTheSummaryButNotThePerFeatureResults() throws Exception {
    JsonNode run = submitAndFetch(batch());

    assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
    JsonNode result = run.get("result");
    assertThat(result.get("engineVersion").asString()).isEqualTo("0.1.0.dev0");
    assertThat(result.get("profileEngineVersion").asString()).isEqualTo("0.1.0.dev0");
    assertThat(result.get("rowCount").asLong()).isEqualTo(2);
    assertThat(result.get("summary")).isEqualTo(json.readTree(DRIFT_RESPONSE).get("summary"));
    assertThat(result.get("config").get("numeric").get("psi_threshold").asDouble()).isEqualTo(0.2);
    assertThat(result.get("issues").isArray()).isTrue();
    assertThat(result.get("warnings").isArray()).isTrue();
    assertThat(result.has("features")).isFalse();
    assertThat(run.get("error").isNull()).isTrue();
    assertThat(run.get("startedAt").isNull()).isFalse();
    assertThat(run.get("finishedAt").isNull()).isFalse();
  }

  @Test
  void aFailedRunShowsTheProblemTypeMessageAndDetails() throws Exception {
    doThrow(
            new AnalyticsRejectedException(
                422,
                "schema-mismatch",
                "The current batch is missing monitored columns.",
                List.of(Map.of("column", "age"))))
        .when(analytics)
        .drift(any(), any(), anyString(), any());

    JsonNode run = submitAndFetch(batch());

    assertThat(run.get("status").asString()).isEqualTo("FAILED");
    JsonNode error = run.get("error");
    assertThat(error.get("type").asString()).isEqualTo(PROBLEM + "schema-mismatch");
    assertThat(error.get("message").asString())
        .isEqualTo("The current batch is missing monitored columns.");
    assertThat(error.get("details")).isEqualTo(json.readTree("[{\"column\": \"age\"}]"));
    assertThat(run.get("result").isNull()).isTrue();
  }

  @Test
  void theConfigIsEchoedAndSentToTheEngine() throws Exception {
    JsonNode run =
        submitAndFetch(batch().field("config", "{\"numeric\": {\"psi_threshold\": 0.3}}"));

    JsonNode expected = json.readTree("{\"numeric\": {\"psi_threshold\": 0.3}}");
    assertThat(run.get("requestedConfig")).isEqualTo(expected);
    assertThat(configSent.get()).isEqualTo(expected);
  }

  @Test
  void withoutConfigTheRequestedConfigIsNull() throws Exception {
    JsonNode run = submitAndFetch(batch());

    assertThat(run.get("requestedConfig").isNull()).isTrue();
    assertThat(configSent.get()).isNull();
  }

  @Test
  void malformedConfigIs400AndNothingIsStored() throws Exception {
    for (String config : List.of("{not json", "[1, 2]")) {
      HttpResponse<String> response = submit(batch().field("config", config));

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(problem(response).get("type").asString()).isEqualTo(PROBLEM + "invalid-parameter");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_run", Integer.class)).isZero();
  }

  @Test
  void aModelWithoutAReferenceIs409() throws Exception {
    UUID other = models.create("churn", TaskType.REGRESSION, null, null).getId();

    HttpResponse<String> response = submit(other, batch());

    assertThat(response.statusCode()).isEqualTo(409);
    JsonNode problem = problem(response);
    assertThat(problem.get("type").asString()).isEqualTo(PROBLEM + "reference-required");
    assertThat(problem.get("detail").asString()).contains(other.toString());
  }

  @Test
  void anUnknownModelIs404() throws Exception {
    HttpResponse<String> response = submit(UUID.randomUUID(), batch());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(problem(response).get("type").asString()).isEqualTo(PROBLEM + "model-not-found");
  }

  @Test
  void anEmptyBatchIs400AndTheEngineIsNotCalled() throws Exception {
    HttpResponse<String> response =
        submit(new Multipart().file("current", "today.csv", "text/csv", new byte[0]));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(problem(response).get("type").asString()).isEqualTo(PROBLEM + "empty-upload");
    verify(analytics, never()).drift(any(), any(), anyString(), any());
  }

  @Test
  void aMissingCurrentPartIs400() throws Exception {
    HttpResponse<String> response =
        submit(
            new Multipart()
                .file("file", "today.csv", "text/csv", "a\n1\n".getBytes(StandardCharsets.UTF_8)));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(problem(response).get("detail").asString()).contains("'current'");
  }

  @Test
  void anUnknownRunIs404() throws Exception {
    UUID id = UUID.randomUUID();

    HttpResponse<String> response = get("/api/v1/runs/" + id);

    assertThat(response.statusCode()).isEqualTo(404);
    JsonNode problem = problem(response);
    assertThat(problem.get("type").asString()).isEqualTo(PROBLEM + "run-not-found");
    assertThat(problem.get("detail").asString())
        .isEqualTo("Monitoring run " + id + " does not exist");
  }

  @Test
  void aMalformedRunIdIs400() throws Exception {
    HttpResponse<String> response = get("/api/v1/runs/not-a-uuid");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(problem(response).get("detail").asString()).isEqualTo("'runId' must be a UUID");
  }
}
