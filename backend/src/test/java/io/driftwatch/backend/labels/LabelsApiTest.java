package io.driftwatch.backend.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import io.driftwatch.backend.analytics.PerformanceResult;
import io.driftwatch.backend.analytics.ProfileResult;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import io.driftwatch.backend.run.RunService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Label upload over HTTP, with the engine mocked. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LabelsApiTest {

  private static final String PROBLEM = "urn:driftwatch:problem:";
  private static final byte[] BATCH = "id,score\na,0.2\nb,0.8\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LABELS = "id,defaulted\nb,1\na,0\n".getBytes(StandardCharsets.UTF_8);
  private static final String PROFILE =
      """
      {"format_version": 1, "engine_version": "0.1.0.dev0", "n_rows": 3, "marker": "pinned",
       "dataset_schema": {"columns": []}, "features": {}, "issues": []}
      """;
  private static final String JOIN =
      """
      {"n_current_rows": 2, "n_label_rows": 2, "n_current_without_id": 0,
       "n_labels_without_id": 0, "n_matched": 2, "n_missing_labels": 0,
       "n_invalid_labels": 0, "n_joined": 2, "n_unmatched_labels": 0, "join_rate": 1.0}
      """;
  private static final String METRICS =
      """
      {"status": "evaluated", "task_type": "binary_classification", "reason": null,
       "n_evaluated": 2, "n_missing_predictions": 0, "n_invalid_predictions": 0,
       "positive_label": "true", "metrics": {"accuracy": 1.0}, "notes": []}
      """;
  private static final String CONFIG =
      """
      {"min_evaluated": 30, "probability_threshold": 0.5, "positive_label": null}
      """;
  private static final String DRIFT =
      """
      {"engine_version": "0.1.0.dev0", "n_rows": 2, "summary": {}, "features": []}
      """;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @Autowired private ReferenceService references;
  @Autowired private RunService runs;
  @MockitoBean private AnalyticsClient analytics;

  private UUID runId;
  private final AtomicReference<byte[]> profileSent = new AtomicReference<>();
  private final AtomicReference<byte[]> batchSent = new AtomicReference<>();
  private final AtomicReference<byte[]> labelsSent = new AtomicReference<>();
  private final AtomicReference<JsonNode> configSent = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    TestDatabase.clear(jdbc);
    UUID modelId =
        models
            .create("credit-risk", TaskType.BINARY_CLASSIFICATION, "defaulted", "score", "id")
            .getId();
    doAnswer(inv -> new ProfileResult(json.readTree(PROFILE), json.readTree("[]")))
        .when(analytics)
        .profile(any(), anyString(), any());
    doAnswer(inv -> new DriftResult(json.readTree(DRIFT)))
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    references.register(modelId, "ref.csv", new ByteArrayInputStream(BATCH), null);
    runId = runs.submit(modelId, "today.csv", new ByteArrayInputStream(BATCH), null).getId();
    await().atMost(Duration.ofSeconds(20)).until(() -> runs.get(runId).getStatus().isTerminal());
    engineJoins(JOIN);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  private static byte[] read(Resource resource) throws Exception {
    try (InputStream in = resource.getInputStream()) {
      return in.readAllBytes();
    }
  }

  private void engineJoins(String join) {
    engineReturns(join, METRICS);
  }

  private void engineReturns(String join, String metrics) {
    doAnswer(
            inv -> {
              profileSent.set(inv.getArgument(0));
              batchSent.set(read(inv.getArgument(1)));
              labelsSent.set(read(inv.getArgument(3)));
              configSent.set(inv.getArgument(5));
              return new PerformanceResult(
                  json.readTree(join), json.readTree(metrics), json.readTree(CONFIG), "0.1.0.dev0");
            })
        .when(analytics)
        .performance(any(), any(), anyString(), any(), anyString(), any());
  }

  private String path(UUID run) {
    return "/api/v1/runs/" + run + "/labels";
  }

  private HttpResponse<String> upload(UUID run, Multipart body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(run)))
            .header("Content-Type", body.contentType())
            .POST(body.publisher())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(byte[] labels) throws Exception {
    return upload(runId, new Multipart().file("labels", "labels.csv", "text/csv", labels));
  }

  private HttpResponse<String> get(UUID run) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(run))).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private JsonNode problem(HttpResponse<String> response, int status, String kind) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asString()).isEqualTo(PROBLEM + kind);
    return problem;
  }

  @Test
  void uploadAnswers201WithTheJoinReport() throws Exception {
    HttpResponse<String> response = upload(LABELS);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + path(runId));
    JsonNode body = json.readTree(response.body());
    assertThat(body.get("runId").asString()).isEqualTo(runId.toString());
    assertThat(body.get("engineVersion").asString()).isEqualTo("0.1.0.dev0");
    assertThat(body.get("join")).isEqualTo(json.readTree(JOIN));
    assertThat(body.get("metrics")).isEqualTo(json.readTree(METRICS));
    assertThat(body.get("config")).isEqualTo(json.readTree(CONFIG));
    assertThat(body.get("dataset").get("byteSize").asLong()).isEqualTo(LABELS.length);
    assertThat(body.get("dataset").get("originalFilename").asString()).isEqualTo("labels.csv");
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM dataset WHERE id = (SELECT dataset_id FROM run_labels)",
                String.class))
        .isEqualTo("LABELS");
  }

  @Test
  void theEngineGetsThePinnedProfileTheRunsBatchAndTheLabels() throws Exception {
    upload(LABELS);

    assertThat(new String(profileSent.get(), StandardCharsets.UTF_8)).contains("\"pinned\"");
    assertThat(batchSent.get()).isEqualTo(BATCH);
    assertThat(labelsSent.get()).isEqualTo(LABELS);
  }

  @Test
  void getReturnsTheNewestUploadAndHistoryIsKept() throws Exception {
    upload(LABELS);
    engineJoins(JOIN.replace("\"n_joined\": 2", "\"n_joined\": 1"));
    JsonNode second = json.readTree(upload("id,defaulted\nb,1\n".getBytes()).body());

    HttpResponse<String> response = get(runId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body())).isEqualTo(second);
    assertThat(second.get("join").get("n_joined").asInt()).isEqualTo(1);
    assertThat(count("run_labels")).isEqualTo(2);
  }

  @Test
  void anEngineRejectionIsPassedThroughAndNothingIsRecorded() throws Exception {
    doThrow(
            new AnalyticsRejectedException(
                422,
                "duplicate-record-ids",
                "record ids must be unique; 1 id(s) repeat in the labels CSV",
                List.of(Map.of("part", "labels", "n_duplicated_ids", 1))))
        .when(analytics)
        .performance(any(), any(), anyString(), any(), anyString(), any());

    JsonNode problem = problem(upload(LABELS), 422, "duplicate-record-ids");

    assertThat(problem.get("errors").get(0).get("n_duplicated_ids").asInt()).isEqualTo(1);
    assertThat(count("run_labels")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dataset WHERE role = 'LABELS'", Integer.class))
        .isZero();
  }

  @Test
  void aJoinReportWithoutCountsIsAContractViolation() throws Exception {
    engineJoins("{\"join_rate\": 1.0}");

    problem(upload(LABELS), 502, "analytics-protocol-error");
    assertThat(count("run_labels")).isZero();
  }

  @Test
  void aConfigIsPassedToTheEngineAndWithoutOneNoneIsSent() throws Exception {
    upload(LABELS);
    assertThat(configSent.get()).isNull();

    upload(
        runId,
        new Multipart()
            .file("labels", "labels.csv", "text/csv", LABELS)
            .field("config", "{\"positive_label\": \"deny\"}"));

    assertThat(configSent.get()).isEqualTo(json.readTree("{\"positive_label\": \"deny\"}"));
  }

  @Test
  void aMalformedConfigIs400AndTheEngineIsNotCalled() throws Exception {
    for (String config : List.of("{nope", "[1]")) {
      problem(
          upload(
              runId,
              new Multipart()
                  .file("labels", "labels.csv", "text/csv", LABELS)
                  .field("config", config)),
          400,
          "invalid-parameter");
    }
    verify(analytics, never()).performance(any(), any(), anyString(), any(), anyString(), any());
  }

  @Test
  void aResponseWithoutMetricsIsAContractViolation() throws Exception {
    engineReturns(JOIN, "{\"metrics\": {}}");

    problem(upload(LABELS), 502, "analytics-protocol-error");
    assertThat(count("run_labels")).isZero();
  }

  @Test
  void labelsForAnUnknownRunAre404AndTheEngineIsNotCalled() throws Exception {
    problem(
        upload(UUID.randomUUID(), new Multipart().file("labels", "l.csv", "text/csv", LABELS)),
        404,
        "run-not-found");
    verify(analytics, never()).performance(any(), any(), anyString(), any(), anyString(), any());
  }

  @Test
  void aRunWithoutLabelsIs404LabelsNotFound() throws Exception {
    JsonNode problem = problem(get(runId), 404, "labels-not-found");

    assertThat(problem.get("detail").asString())
        .isEqualTo("Monitoring run " + runId + " has no labels yet");
  }

  @Test
  void labelsOfAnUnknownRunAre404RunNotFound() throws Exception {
    problem(get(UUID.randomUUID()), 404, "run-not-found");
  }

  @Test
  void anEmptyLabelsFileIs400() throws Exception {
    problem(upload(new byte[0]), 400, "empty-upload");
    verify(analytics, never()).performance(any(), any(), anyString(), any(), anyString(), any());
  }

  @Test
  void aMissingLabelsPartIs400() throws Exception {
    HttpResponse<String> response =
        upload(runId, new Multipart().file("file", "l.csv", "text/csv", LABELS));

    assertThat(problem(response, 400, "invalid-request").get("detail").asString())
        .contains("'labels'");
  }
}
