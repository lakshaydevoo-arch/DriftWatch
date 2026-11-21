package io.driftwatch.backend.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftwatch.backend.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP contract of /api/v1/models against a real PostgreSQL. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ModelApiTest {

  private static final String PROBLEM = "urn:driftwatch:problem:";

  private final HttpClient client = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void emptyRegistry() {
    // Datasets reference models (ON DELETE RESTRICT), so delete them first.
    jdbc.update("DELETE FROM dataset");
    jdbc.update("DELETE FROM monitored_model");
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private HttpResponse<String> post(String body) throws Exception {
    return post(body, "application/json");
  }

  private HttpResponse<String> post(String body, String contentType) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri("/api/v1/models"))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private JsonNode assertProblem(HttpResponse<String> response, int status, String kind) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    JsonNode problem = body(response);
    assertThat(problem.get("type").asString()).isEqualTo(PROBLEM + kind);
    assertThat(problem.get("status").asInt()).isEqualTo(status);
    assertThat(problem.get("title").asString()).isNotBlank();
    assertThat(problem.get("detail").asString()).isNotBlank();
    return problem;
  }

  private static String model(String name) {
    return """
    {"name": "%s", "taskType": "BINARY_CLASSIFICATION",
     "targetColumn": "defaulted", "predictionColumn": "default_probability"}
    """
        .formatted(name);
  }

  // --- create and read ---------------------------------------------------------------

  @Test
  void createReturns201WithLocationAndTheStoredModel() throws Exception {
    HttpResponse<String> response = post(model("credit-risk"));

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode created = body(response);
    UUID id = UUID.fromString(created.get("id").asString());
    assertThat(id.version()).isEqualTo(7); // time-ordered UUID
    assertThat(created.get("name").asString()).isEqualTo("credit-risk");
    assertThat(created.get("taskType").asString()).isEqualTo("BINARY_CLASSIFICATION");
    assertThat(created.get("targetColumn").asString()).isEqualTo("defaulted");
    assertThat(created.get("predictionColumn").asString()).isEqualTo("default_probability");
    // ISO-8601 instant in UTC, e.g. 2026-11-21T16:10:00.123456Z
    assertThat(created.get("createdAt").asString()).matches("\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z");
    assertThat(response.headers().firstValue("Location"))
        .hasValue(uri("/api/v1/models/" + id).toString());
  }

  @Test
  void readingAModelReturnsExactlyWhatCreateReturned() throws Exception {
    // Guards against precision drift: PostgreSQL stores microseconds, so a nanosecond timestamp
    // returned by create would not match the value read back.
    HttpResponse<String> created = post(model("credit-risk"));
    String location = created.headers().firstValue("Location").orElseThrow();

    HttpResponse<String> read = get(URI.create(location).getPath());

    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(body(read)).isEqualTo(body(created));
  }

  @Test
  void optionalColumnsMayBeOmitted() throws Exception {
    HttpResponse<String> response = post("{\"name\": \"churn\", \"taskType\": \"REGRESSION\"}");

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(body(response).get("targetColumn").isNull()).isTrue();
    assertThat(body(response).get("predictionColumn").isNull()).isTrue();
  }

  @Test
  void columnNamesMayContainSpaces() throws Exception {
    HttpResponse<String> response =
        post(
            "{\"name\": \"tumours\", \"taskType\": \"BINARY_CLASSIFICATION\","
                + " \"targetColumn\": \"is malignant\", \"predictionColumn\": \"p malignant\"}");

    assertThat(response.statusCode()).isEqualTo(201);
  }

  @Test
  void unknownModelIs404() throws Exception {
    JsonNode problem =
        assertProblem(get("/api/v1/models/" + UUID.randomUUID()), 404, "model-not-found");

    assertThat(problem.get("detail").asString()).startsWith("No model with id ");
  }

  @Test
  void nonUuidIdIs400() throws Exception {
    JsonNode problem = assertProblem(get("/api/v1/models/not-a-uuid"), 400, "invalid-parameter");

    assertThat(problem.get("detail").asString()).isEqualTo("'id' must be a UUID");
  }

  // --- uniqueness ----------------------------------------------------------------------

  @Test
  void duplicateNameIs409() throws Exception {
    assertThat(post(model("credit-risk")).statusCode()).isEqualTo(201);

    JsonNode problem = assertProblem(post(model("credit-risk")), 409, "model-name-taken");

    assertThat(problem.get("detail").asString())
        .isEqualTo("A model named 'credit-risk' already exists");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitored_model", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentCreatesWithTheSameNameProduceExactlyOneModel() throws Exception {
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(() -> {
              start.await();
              return post(model("race")).statusCode();
            }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }

      // Uniqueness comes from the database constraint, so a race cannot create duplicates.
      assertThat(statuses).containsOnly(201, 409);
      assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitored_model", Integer.class))
        .isEqualTo(1);
  }

  // --- validation ----------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "Credit-Risk",
        "credit risk",
        "-leading-hyphen",
        "trailing-hyphen-",
        "under_score",
        " padded "
      })
  void invalidNamesAreRejected(String name) throws Exception {
    JsonNode problem = assertProblem(post(model(name)), 400, "invalid-request");

    assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("name");
  }

  @Test
  void nameLengthLimitIs100() throws Exception {
    assertThat(post(model("m".repeat(100))).statusCode()).isEqualTo(201);
    assertProblem(post(model("n".repeat(101))), 400, "invalid-request");
  }

  @Test
  void missingRequiredFieldsAreAllReported() throws Exception {
    JsonNode problem = assertProblem(post("{}"), 400, "invalid-request");

    assertThat(problem.get("errors").valueStream().map(e -> e.get("field").asString()))
        .containsExactly("name", "taskType");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "'  '        | targetColumn",
        "' label'    | targetColumn",
        "'label '    | targetColumn",
        "'a\\nb'     | targetColumn"
      })
  void invalidColumnNamesAreRejected(String column, String field) throws Exception {
    String body =
        "{\"name\": \"m\", \"taskType\": \"REGRESSION\", \"targetColumn\": \"%s\"}"
            .formatted(column);

    JsonNode problem = assertProblem(post(body), 400, "invalid-request");

    assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo(field);
  }

  @Test
  void predictionColumnMustDifferFromTarget() throws Exception {
    String body =
        "{\"name\": \"m\", \"taskType\": \"REGRESSION\","
            + " \"targetColumn\": \"price\", \"predictionColumn\": \"price\"}";

    JsonNode problem = assertProblem(post(body), 400, "invalid-request");

    JsonNode error = problem.get("errors").get(0);
    assertThat(error.get("field").asString()).isEqualTo("predictionColumnDifferentFromTarget");
    assertThat(error.get("message").asString())
        .isEqualTo("must be a different column from targetColumn");
  }

  @Test
  void unknownTaskTypeListsAllowedValues() throws Exception {
    JsonNode problem =
        assertProblem(
            post("{\"name\": \"m\", \"taskType\": \"CLUSTERING\"}"), 400, "malformed-request-body");

    assertThat(problem.get("detail").asString())
        .isEqualTo(
            "Invalid value for 'taskType'; expected one of"
                + " [BINARY_CLASSIFICATION, MULTICLASS_CLASSIFICATION, REGRESSION].");
  }

  @Test
  void unknownFieldsAreRejected() throws Exception {
    JsonNode problem =
        assertProblem(
            post("{\"name\": \"m\", \"tasktype\": \"REGRESSION\"}"), 400, "malformed-request-body");

    assertThat(problem.get("detail").asString()).isEqualTo("Unknown field 'tasktype'.");
  }

  @Test
  void malformedJsonIsRejectedWithoutParserDetails() throws Exception {
    JsonNode problem = assertProblem(post("{\"name\": "), 400, "malformed-request-body");

    assertThat(problem.get("detail").asString()).isEqualTo("The request body is not valid JSON.");
  }

  @Test
  void nonJsonContentTypeIs415() throws Exception {
    HttpResponse<String> response = post("name=m", "application/x-www-form-urlencoded");

    assertThat(response.statusCode()).isEqualTo(415);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
  }

  // --- listing -------------------------------------------------------------------------

  @Test
  void listIsSortedByNameAndPaginated() throws Exception {
    for (String name : List.of("delta", "alpha", "charlie", "bravo", "echo")) {
      post(model(name));
    }

    JsonNode first = body(get("/api/v1/models?page=0&size=2"));
    JsonNode last = body(get("/api/v1/models?page=2&size=2"));

    assertThat(first.get("items").valueStream().map(m -> m.get("name").asString()))
        .containsExactly("alpha", "bravo");
    assertThat(first.get("page").asInt()).isZero();
    assertThat(first.get("size").asInt()).isEqualTo(2);
    assertThat(first.get("totalItems").asLong()).isEqualTo(5);
    assertThat(first.get("totalPages").asInt()).isEqualTo(3);
    assertThat(last.get("items").valueStream().map(m -> m.get("name").asString()))
        .containsExactly("echo");
  }

  @Test
  void emptyRegistryListsNothing() throws Exception {
    JsonNode page = body(get("/api/v1/models"));

    assertThat(page.get("items").isEmpty()).isTrue();
    assertThat(page.get("totalItems").asLong()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(20);
  }

  @ParameterizedTest
  @ValueSource(strings = {"page=-1", "size=0", "size=101"})
  void outOfRangePagingIsRejected(String query) throws Exception {
    JsonNode problem = assertProblem(get("/api/v1/models?" + query), 400, "invalid-parameter");

    assertThat(problem.get("errors").get(0).get("field").asString())
        .isEqualTo(query.substring(0, query.indexOf('=')));
  }

  @Test
  void nonNumericPagingIsRejected() throws Exception {
    JsonNode problem = assertProblem(get("/api/v1/models?size=lots"), 400, "invalid-parameter");

    assertThat(problem.get("detail").asString()).isEqualTo("'size' must be an integer");
  }
}
