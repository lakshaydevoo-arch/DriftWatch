package io.driftwatch.backend.run;

import static io.driftwatch.backend.run.RunFixtures.CURRENT_CSV;
import static io.driftwatch.backend.run.RunFixtures.awaitFinished;
import static io.driftwatch.backend.run.RunFixtures.registerReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsUnavailableException;
import io.driftwatch.backend.analytics.DriftResult;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

/** Per-feature results and run history over HTTP, with the engine mocked. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RunResultsApiTest {

  private static final String PROBLEM = "urn:driftwatch:problem:";

  /** Three features in schema order; two drifted. */
  private static final String RESPONSE =
      """
      {"engine_version": "0.1.0.dev0", "profile_engine_version": "0.1.0.dev0",
       "config": {"numeric": {"psi_threshold": 0.2}}, "n_rows": 2,
       "summary": {"n_features": 3, "n_evaluated": 3, "n_insufficient_data": 0,
                   "n_drifted": 2, "n_with_quality_flags": 0, "drift_share": 0.667,
                   "dataset_drift": true},
       "features": [
         {"name": "age", "logical_type": "numeric", "status": "evaluated",
          "drift_detected": true, "flags": [], "drift": {"kind": "numeric", "psi": 0.41}},
         {"name": "region", "logical_type": "categorical", "status": "evaluated",
          "drift_detected": false, "flags": [], "drift": {"kind": "categorical"}},
         {"name": "income", "logical_type": "numeric", "status": "evaluated",
          "drift_detected": true, "flags": ["missing_rate_change"], "drift": null}],
       "issues": [], "warnings": []}
      """;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @Autowired private ReferenceService references;
  @Autowired private RunService runs;
  @MockitoBean private AnalyticsClient analytics;

  private UUID modelId;

  @BeforeEach
  void setUp() throws Exception {
    TestDatabase.clear(jdbc);
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
    registerReference(references, analytics, modelId, "first");
    doAnswer(invocation -> new DriftResult(json.readTree(RESPONSE)))
        .when(analytics)
        .drift(any(), any(), anyString(), any());
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  private UUID submit(UUID model) throws Exception {
    return runs.submit(model, "today.csv", new ByteArrayInputStream(CURRENT_CSV), null).getId();
  }

  private UUID finishedRun() throws Exception {
    UUID id = submit(modelId);
    awaitFinished(runs, id);
    return id;
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json.readTree(response.body());
  }

  private static List<String> names(JsonNode features) {
    List<String> names = new ArrayList<>();
    features.forEach(feature -> names.add(feature.get("name").asString()));
    return names;
  }

  // --- per-feature results

  @Test
  void featuresAreReturnedInSchemaOrderExactlyAsTheEngineReturnedThem() throws Exception {
    UUID run = finishedRun();

    JsonNode body = ok("/api/v1/runs/" + run + "/features");

    assertThat(body.get("runId").asString()).isEqualTo(run.toString());
    assertThat(body.get("totalFeatures").asInt()).isEqualTo(3);
    assertThat(body.get("returnedFeatures").asInt()).isEqualTo(3);
    assertThat(body.get("features")).isEqualTo(json.readTree(RESPONSE).get("features"));
  }

  @Test
  void driftedTrueKeepsOnlyDriftedFeatures() throws Exception {
    UUID run = finishedRun();

    JsonNode body = ok("/api/v1/runs/" + run + "/features?drifted=true");

    assertThat(names(body.get("features"))).containsExactly("age", "income");
    assertThat(body.get("totalFeatures").asInt()).isEqualTo(3);
    assertThat(body.get("returnedFeatures").asInt()).isEqualTo(2);
  }

  @Test
  void driftedFalseKeepsOnlyTheOthers() throws Exception {
    UUID run = finishedRun();

    assertThat(names(ok("/api/v1/runs/" + run + "/features?drifted=false").get("features")))
        .containsExactly("region");
  }

  @Test
  void aMalformedDriftedFilterIs400() throws Exception {
    UUID run = finishedRun();

    HttpResponse<String> response = get("/api/v1/runs/" + run + "/features?drifted=maybe");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json.readTree(response.body()).get("type").asString())
        .isEqualTo(PROBLEM + "invalid-parameter");
  }

  @Test
  void anUnfinishedRunHasNoFeaturesYet() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              release.await(10, TimeUnit.SECONDS);
              return new DriftResult(json.readTree(RESPONSE));
            })
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    UUID run = submit(modelId);

    HttpResponse<String> response = get("/api/v1/runs/" + run + "/features");
    release.countDown();
    awaitFinished(runs, run);

    assertThat(response.statusCode()).isEqualTo(409);
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asString()).isEqualTo(PROBLEM + "run-not-succeeded");
    assertThat(problem.get("runStatus").asString()).isIn("PENDING", "RUNNING");
  }

  @Test
  void aFailedRunHasNoFeatures() throws Exception {
    doThrow(new AnalyticsUnavailableException("down", null))
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    UUID run = finishedRun();

    HttpResponse<String> response = get("/api/v1/runs/" + run + "/features");

    assertThat(response.statusCode()).isEqualTo(409);
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("runStatus").asString()).isEqualTo("FAILED");
    assertThat(problem.get("detail").asString()).contains("is FAILED");
  }

  @Test
  void featuresOfAnUnknownRunAre404() throws Exception {
    HttpResponse<String> response = get("/api/v1/runs/" + UUID.randomUUID() + "/features");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(json.readTree(response.body()).get("type").asString())
        .isEqualTo(PROBLEM + "run-not-found");
  }

  @Test
  void theRunViewOmitsFeaturesButKeepsTheSummary() throws Exception {
    UUID run = finishedRun();

    JsonNode result = ok("/api/v1/runs/" + run).get("result");

    assertThat(result.has("features")).isFalse();
    assertThat(result.get("summary").get("n_drifted").asInt()).isEqualTo(2);
    // The features are still stored in full.
    assertThat(
            jdbc.queryForObject(
                "SELECT jsonb_array_length(result -> 'features') FROM monitoring_run WHERE id = ?",
                Integer.class,
                run))
        .isEqualTo(3);
  }

  // --- run history

  @Test
  void historyListsAModelsRunsNewestFirstInStablePages() throws Exception {
    UUID first = finishedRun();
    UUID second = finishedRun();
    UUID third = finishedRun();
    UUID other = models.create("churn", TaskType.REGRESSION, null, null).getId();
    registerReference(references, analytics, other, "other");
    doAnswer(invocation -> new DriftResult(json.readTree(RESPONSE)))
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    awaitFinished(runs, submit(other));

    JsonNode page0 = ok("/api/v1/models/" + modelId + "/runs?size=2");
    JsonNode page1 = ok("/api/v1/models/" + modelId + "/runs?size=2&page=1");

    assertThat(ids(page0)).containsExactly(third.toString(), second.toString());
    assertThat(ids(page1)).containsExactly(first.toString());
    for (JsonNode page : List.of(page0, page1)) {
      assertThat(page.get("size").asInt()).isEqualTo(2);
      assertThat(page.get("totalItems").asLong()).isEqualTo(3);
      assertThat(page.get("totalPages").asInt()).isEqualTo(2);
    }
    assertThat(page1.get("page").asInt()).isEqualTo(1);
  }

  @Test
  void historyItemsLookLikeTheRunViewWithoutFeatures() throws Exception {
    UUID run = finishedRun();

    JsonNode item = ok("/api/v1/models/" + modelId + "/runs").get("items").get(0);

    assertThat(item).isEqualTo(ok("/api/v1/runs/" + run));
    assertThat(item.get("result").has("features")).isFalse();
  }

  @Test
  void historyShowsRunsInEveryState() throws Exception {
    doThrow(new AnalyticsUnavailableException("down", null))
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    finishedRun();

    JsonNode item = ok("/api/v1/models/" + modelId + "/runs").get("items").get(0);

    assertThat(item.get("status").asString()).isEqualTo("FAILED");
    assertThat(item.get("error").get("type").asString())
        .isEqualTo(PROBLEM + "analytics-unavailable");
    assertThat(item.get("result").isNull()).isTrue();
  }

  @Test
  void aModelWithoutRunsHasAnEmptyHistory() throws Exception {
    JsonNode page = ok("/api/v1/models/" + modelId + "/runs");

    assertThat(page.get("items").isEmpty()).isTrue();
    assertThat(page.get("totalItems").asLong()).isZero();
    assertThat(page.get("totalPages").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(20);
  }

  @Test
  void historyOfAnUnknownModelIs404() throws Exception {
    HttpResponse<String> response = get("/api/v1/models/" + UUID.randomUUID() + "/runs");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(json.readTree(response.body()).get("type").asString())
        .isEqualTo(PROBLEM + "model-not-found");
  }

  @Test
  void invalidPagingIs400() throws Exception {
    for (String query : List.of("page=-1", "size=0", "size=101", "page=x")) {
      HttpResponse<String> response = get("/api/v1/models/" + modelId + "/runs?" + query);

      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(json.readTree(response.body()).get("type").asString())
          .isEqualTo(PROBLEM + "invalid-parameter");
    }
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }
}
