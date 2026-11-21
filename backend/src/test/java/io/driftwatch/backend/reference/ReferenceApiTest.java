package io.driftwatch.backend.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.driftwatch.backend.Multipart;
import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsProtocolException;
import io.driftwatch.backend.analytics.AnalyticsRejectedException;
import io.driftwatch.backend.analytics.AnalyticsUnavailableException;
import io.driftwatch.backend.analytics.ProfileResult;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

/**
 * Reference registration over HTTP, with the analytics engine replaced by a mock so every engine
 * outcome can be produced. The real engine is exercised by {@link
 * ReferenceRegistrationEngineContractTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReferenceApiTest {

  private static final byte[] CSV =
      "age,id\n31,a1\n45,a2\n52,a3\n".getBytes(StandardCharsets.UTF_8);

  /** Shaped like the engine's real profile (fields the backend reads). */
  private static final String PROFILE =
      """
      {"format_version": 1, "engine_version": "0.1.0.dev0", "n_rows": 3,
       "dataset_schema": {"columns": [
         {"name": "age", "logical_type": "numeric", "source": "inferred",
          "excluded": null, "integer_coded": false},
         {"name": "id", "logical_type": "categorical", "source": "inferred",
          "excluded": "likely_identifier", "integer_coded": false}]},
       "features": {"age": {"kind": "numeric"}}, "issues": []}
      """;

  private static final String ISSUES =
      """
      [{"code": "schema_violation", "severity": "warning", "message": "m", "column": "age"}]
      """;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @MockitoBean private AnalyticsClient analytics;

  private UUID modelId;
  private final AtomicReference<byte[]> sentToEngine = new AtomicReference<>();
  private final AtomicReference<JsonNode> optionsSentToEngine = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    TestDatabase.clear(jdbc);
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
    engineReturns(PROFILE, ISSUES);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  /** do...().when(mock) form: re-stubbing with when(mock.call()) would invoke the old answer. */
  private void engineReturns(String profile, String issues) {
    doAnswer(
            invocation -> {
              Resource resource = invocation.getArgument(0);
              try (InputStream in = resource.getInputStream()) {
                sentToEngine.set(in.readAllBytes());
              }
              optionsSentToEngine.set(invocation.getArgument(2));
              return new ProfileResult(json.readTree(profile), json.readTree(issues));
            })
        .when(analytics)
        .profile(any(), anyString(), any());
  }

  private void engineThrows(RuntimeException exception) {
    doThrow(exception).when(analytics).profile(any(), anyString(), any());
  }

  private String path(UUID model) {
    return "/api/v1/models/" + model + "/reference";
  }

  private HttpResponse<String> upload(UUID model, Multipart body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(model)))
            .header("Content-Type", body.contentType())
            .POST(body.publisher())
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(UUID model, byte[] csv) throws Exception {
    return upload(model, new Multipart().file("file", "reference.csv", "text/csv", csv));
  }

  private HttpResponse<String> getCurrent(UUID model) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(model))).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private JsonNode assertProblem(HttpResponse<String> response, int status, String kind) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    JsonNode problem = body(response);
    assertThat(problem.get("type").asString()).isEqualTo("urn:driftwatch:problem:" + kind);
    return problem;
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  // --- success ------------------------------------------------------------------------

  @Test
  void registerStoresTheFileProfilesItAndReturnsTheSchema() throws Exception {
    HttpResponse<String> response = upload(modelId, CSV);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location"))
        .hasValue("http://localhost:" + port + path(modelId));
    JsonNode reference = body(response);
    assertThat(UUID.fromString(reference.get("id").asString()).version()).isEqualTo(7);
    assertThat(reference.get("modelId").asString()).isEqualTo(modelId.toString());
    assertThat(reference.get("rowCount").asLong()).isEqualTo(3);
    assertThat(reference.get("engineVersion").asString()).isEqualTo("0.1.0.dev0");
    JsonNode dataset = reference.get("dataset");
    assertThat(dataset.get("sha256").asString()).isEqualTo(sha256(CSV));
    assertThat(dataset.get("byteSize").asLong()).isEqualTo(CSV.length);
    assertThat(dataset.get("originalFilename").asString()).isEqualTo("reference.csv");
    assertThat(reference.get("columns"))
        .isEqualTo(
            json.readTree(
                """
                [{"name": "age", "logicalType": "numeric", "monitored": true,
                  "excludedReason": null},
                 {"name": "id", "logicalType": "categorical", "monitored": false,
                  "excludedReason": "likely_identifier"}]
                """));
    assertThat(reference.get("issues").get(0).get("code").asString()).isEqualTo("schema_violation");
  }

  @Test
  void theEngineReceivesExactlyTheUploadedBytes() throws Exception {
    upload(modelId, CSV);

    assertThat(sentToEngine.get()).isEqualTo(CSV);
    verify(analytics).profile(any(), eq("reference.csv"), any());
  }

  @Test
  void profileIsStoredExactlyAsTheEngineReturnedIt() throws Exception {
    upload(modelId, CSV);

    String stored =
        jdbc.queryForObject("SELECT profile::text FROM reference_profile", String.class);
    assertThat(json.readTree(stored)).isEqualTo(json.readTree(PROFILE));
    // Stored as JSONB, so it can be queried in SQL.
    assertThat(
            jdbc.queryForObject("SELECT profile->>'n_rows' FROM reference_profile", String.class))
        .isEqualTo("3");
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM dataset WHERE id = (SELECT dataset_id FROM reference_profile)",
                String.class))
        .isEqualTo("REFERENCE");
  }

  @Test
  void getReturnsWhatRegisterReturned() throws Exception {
    JsonNode registered = body(upload(modelId, CSV));

    HttpResponse<String> current = getCurrent(modelId);

    assertThat(current.statusCode()).isEqualTo(200);
    assertThat(body(current)).isEqualTo(registered);
  }

  @Test
  void reRegisteringKeepsHistoryAndTheNewestIsCurrent() throws Exception {
    upload(modelId, CSV);
    byte[] newer = "age,id\n60,b1\n61,b2\n62,b3\n".getBytes(StandardCharsets.UTF_8);
    JsonNode second = body(upload(modelId, newer));

    assertThat(body(getCurrent(modelId))).isEqualTo(second);
    assertThat(count("reference_profile")).isEqualTo(2);
    assertThat(count("dataset")).isEqualTo(2);
  }

  @Test
  void optionsArePassedToTheEngine() throws Exception {
    upload(
        modelId,
        new Multipart()
            .file("file", "reference.csv", "text/csv", CSV)
            .field("options", "{\"exclude\": [\"id\"]}"));

    assertThat(optionsSentToEngine.get()).isEqualTo(json.readTree("{\"exclude\": [\"id\"]}"));
  }

  @Test
  void withoutOptionsTheEngineGetsNone() throws Exception {
    upload(modelId, CSV);

    assertThat(optionsSentToEngine.get()).isNull();
  }

  // --- the engine says no ---------------------------------------------------------------

  @Test
  void engineRejectionIsPassedThroughAndNothingIsRecorded() throws Exception {
    engineThrows(
        new AnalyticsRejectedException(
            422,
            "invalid-csv",
            "'file': duplicate column names: ['a']",
            List.of(Map.of("loc", List.of("file")))));

    JsonNode problem = assertProblem(upload(modelId, CSV), 422, "invalid-csv");

    assertThat(problem.get("detail").asString()).isEqualTo("'file': duplicate column names: ['a']");
    assertThat(problem.get("errors").get(0).get("loc").get(0).asString()).isEqualTo("file");
    assertThat(count("dataset")).isZero();
    assertThat(count("reference_profile")).isZero();
  }

  @Test
  void engineSizeRejectionIs413() throws Exception {
    engineThrows(new AnalyticsRejectedException(413, "upload-too-large", "too big", List.of()));

    JsonNode problem = assertProblem(upload(modelId, CSV), 413, "upload-too-large");

    assertThat(problem.has("errors")).isFalse();
  }

  @Test
  void unavailableEngineIs503WithRetryAfterAndNoInternals() throws Exception {
    engineThrows(
        new AnalyticsUnavailableException(
            "Analytics engine at http://10.0.0.5:8000 unavailable during profile", null));

    HttpResponse<String> response = upload(modelId, CSV);

    JsonNode problem = assertProblem(response, 503, "analytics-unavailable");
    assertThat(response.headers().firstValue("Retry-After")).hasValue("30");
    assertThat(response.body()).doesNotContain("10.0.0.5");
    assertThat(problem.get("detail").asString())
        .isEqualTo("The analytics engine is unavailable; try again later.");
    assertThat(count("dataset")).isZero();
  }

  @Test
  void contractViolationIs502() throws Exception {
    engineThrows(new AnalyticsProtocolException("profile has no valid 'n_rows'"));

    assertProblem(upload(modelId, CSV), 502, "analytics-protocol-error");
    assertThat(count("dataset")).isZero();
  }

  @Test
  void profileWithoutRowCountIsAContractViolation() throws Exception {
    engineReturns("{\"engine_version\": \"0.1.0.dev0\", \"dataset_schema\": {}}", "[]");

    assertProblem(upload(modelId, CSV), 502, "analytics-protocol-error");
    assertThat(count("reference_profile")).isZero();
  }

  // --- the backend says no --------------------------------------------------------------

  @Test
  void unknownModelIs404AndTheEngineIsNotCalled() throws Exception {
    assertProblem(upload(UUID.randomUUID(), CSV), 404, "model-not-found");

    verify(analytics, never()).profile(any(), anyString(), any());
  }

  @Test
  void emptyFileIs400AndTheEngineIsNotCalled() throws Exception {
    assertProblem(upload(modelId, new byte[0]), 400, "empty-upload");

    verify(analytics, never()).profile(any(), anyString(), any());
  }

  @Test
  void missingFilePartIs400() throws Exception {
    JsonNode problem =
        assertProblem(
            upload(modelId, new Multipart().field("options", "{}")), 400, "invalid-request");

    assertThat(problem.get("detail").asString()).isEqualTo("Required part 'file' is missing.");
  }

  @Test
  void malformedOptionsAre400() throws Exception {
    JsonNode problem =
        assertProblem(
            upload(
                modelId,
                new Multipart()
                    .file("file", "reference.csv", "text/csv", CSV)
                    .field("options", "{not json")),
            400,
            "invalid-parameter");

    assertThat(problem.get("detail").asString()).isEqualTo("'options' is not valid JSON");
    verify(analytics, never()).profile(any(), anyString(), any());
  }

  @Test
  void optionsMustBeAnObject() throws Exception {
    assertProblem(
        upload(
            modelId,
            new Multipart().file("file", "r.csv", "text/csv", CSV).field("options", "[1, 2]")),
        400,
        "invalid-parameter");
  }

  @Test
  void nonMultipartRequestIs415() throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(modelId)))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofByteArray(CSV))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(415);
  }

  @Test
  void modelWithoutReferenceIs404() throws Exception {
    JsonNode problem = assertProblem(getCurrent(modelId), 404, "reference-not-found");

    assertThat(problem.get("detail").asString())
        .isEqualTo("Model " + modelId + " has no reference dataset yet");
  }

  @Test
  void currentReferenceOfUnknownModelIs404ModelNotFound() throws Exception {
    assertProblem(getCurrent(UUID.randomUUID()), 404, "model-not-found");
  }
}
