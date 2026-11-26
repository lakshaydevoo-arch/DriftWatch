package io.driftwatch.backend.run;

import static io.driftwatch.backend.run.RunFixtures.CURRENT_CSV;
import static io.driftwatch.backend.run.RunFixtures.DRIFT_RESPONSE;
import static io.driftwatch.backend.run.RunFixtures.awaitFinished;
import static io.driftwatch.backend.run.RunFixtures.registerReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.driftwatch.backend.Multipart;
import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.DriftResult;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * With one worker and one queue place, a third concurrent run is refused before anything is stored.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"driftwatch.runs.concurrency=1", "driftwatch.runs.queue-capacity=1"})
@Import(TestcontainersConfiguration.class)
class RunCapacityTest {

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
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  private MonitoringRun submit() throws Exception {
    return runs.submit(modelId, "today.csv", new ByteArrayInputStream(CURRENT_CSV), null);
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  @Test
  void runsBeyondWorkersPlusQueueAreRefusedUntilOneFinishes() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    doAnswer(
            invocation -> {
              maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
              try {
                release.await(10, TimeUnit.SECONDS);
              } finally {
                concurrent.decrementAndGet();
              }
              return new DriftResult(json.readTree(DRIFT_RESPONSE));
            })
        .when(analytics)
        .drift(any(), any(), anyString(), any());

    MonitoringRun first = submit();
    MonitoringRun second = submit();
    int datasetsBefore = count("SELECT count(*) FROM dataset WHERE role = 'CURRENT'");

    assertThatThrownBy(this::submit)
        .isInstanceOf(RunCapacityExceededException.class)
        .hasMessageContaining("limit 2");
    assertThat(count("SELECT count(*) FROM dataset WHERE role = 'CURRENT'"))
        .isEqualTo(datasetsBefore);
    assertThat(count("SELECT count(*) FROM monitoring_run")).isEqualTo(2);

    release.countDown();
    assertThat(awaitFinished(runs, first.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(awaitFinished(runs, second.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(maxConcurrent.get()).isEqualTo(1);

    // Capacity is released when runs finish.
    assertThat(awaitFinished(runs, submit().getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
  }

  @Test
  void aRefusedSubmissionBeforeStorageDoesNotLeakCapacity() throws Exception {
    doAnswer(invocation -> new DriftResult(json.readTree(DRIFT_RESPONSE)))
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    UUID unknown = UUID.randomUUID();

    // Fails after the slot was reserved; the slot must be returned each time.
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(
              () -> runs.submit(unknown, "x.csv", new ByteArrayInputStream(CURRENT_CSV), null))
          .isInstanceOf(io.driftwatch.backend.model.ModelNotFoundException.class);
    }

    MonitoringRun a = submit();
    MonitoringRun b = submit();
    assertThat(awaitFinished(runs, a.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(awaitFinished(runs, b.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
  }

  @Test
  void overHttpAFullExecutorIs503WithRetryAfter() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              release.await(10, TimeUnit.SECONDS);
              return new DriftResult(json.readTree(DRIFT_RESPONSE));
            })
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    MonitoringRun first = submit();
    MonitoringRun second = submit();

    Multipart body = new Multipart().file("current", "today.csv", "text/csv", CURRENT_CSV);
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:" + port + "/api/v1/models/" + modelId + "/runs"))
                    .header("Content-Type", body.contentType())
                    .POST(body.publisher())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    release.countDown();
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Retry-After")).hasValue("30");
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("type").asString())
        .isEqualTo("urn:driftwatch:problem:run-capacity-exceeded");
    awaitFinished(runs, first.getId());
    awaitFinished(runs, second.getId());
    assertThat(count("SELECT count(*) FROM monitoring_run")).isEqualTo(2);
  }
}
