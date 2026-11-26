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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsProtocolException;
import io.driftwatch.backend.analytics.AnalyticsRejectedException;
import io.driftwatch.backend.analytics.AnalyticsUnavailableException;
import io.driftwatch.backend.analytics.DriftResult;
import io.driftwatch.backend.dataset.Dataset;
import io.driftwatch.backend.dataset.DatasetRole;
import io.driftwatch.backend.model.ModelNotFoundException;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run lifecycle against real PostgreSQL and the real executor, with the engine mocked so every
 * outcome can be produced. Each test waits for its runs to finish before the tables are cleared.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunLifecycleTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @Autowired private ReferenceService references;
  @Autowired private RunService runs;
  @Autowired private RunWorker worker;
  @Autowired private RunRecovery recovery;
  @MockitoBean private AnalyticsClient analytics;

  private UUID modelId;
  private UUID referenceId;
  private final AtomicReference<byte[]> profileSent = new AtomicReference<>();
  private final AtomicReference<byte[]> currentSent = new AtomicReference<>();
  private final AtomicReference<String> filenameSent = new AtomicReference<>();
  private final AtomicReference<JsonNode> configSent = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    TestDatabase.clear(jdbc);
    modelId = models.create("credit-risk", TaskType.BINARY_CLASSIFICATION, null, null).getId();
    referenceId = registerReference(references, analytics, modelId, "first");
    engineReturns(DRIFT_RESPONSE);
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  private void engineReturns(String response) {
    doAnswer(
            invocation -> {
              profileSent.set(invocation.getArgument(0));
              Resource current = invocation.getArgument(1);
              try (InputStream in = current.getInputStream()) {
                currentSent.set(in.readAllBytes());
              }
              filenameSent.set(invocation.getArgument(2));
              configSent.set(invocation.getArgument(3));
              return new DriftResult(json.readTree(response));
            })
        .when(analytics)
        .drift(any(), any(), anyString(), any());
  }

  private void engineThrows(RuntimeException exception) {
    doThrow(exception).when(analytics).drift(any(), any(), anyString(), any());
  }

  private MonitoringRun submit(JsonNode config) throws Exception {
    return runs.submit(modelId, "today.csv", new ByteArrayInputStream(CURRENT_CSV), config);
  }

  private MonitoringRun submitAndWait() throws Exception {
    return awaitFinished(runs, submit(null).getId());
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  @Test
  void aSubmittedRunIsPendingAndPinnedToTheCurrentReference() throws Exception {
    MonitoringRun run = submit(null);

    assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);
    assertThat(run.getModelId()).isEqualTo(modelId);
    assertThat(run.getReferenceId()).isEqualTo(referenceId);
    assertThat(run.getStartedAt()).isNull();
    Dataset dataset = runs.currentDatasetOf(run);
    assertThat(dataset.getRole()).isEqualTo(DatasetRole.CURRENT);
    assertThat(dataset.getByteSize()).isEqualTo(CURRENT_CSV.length);
    assertThat(dataset.getOriginalFilename()).isEqualTo("today.csv");
    awaitFinished(runs, run.getId());
  }

  @Test
  void aSuccessfulRunStoresTheEnginesCompleteResult() throws Exception {
    MonitoringRun run = submitAndWait();

    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(run.getEngineVersion()).isEqualTo("0.1.0.dev0");
    assertThat(json.readTree(run.getResult())).isEqualTo(json.readTree(DRIFT_RESPONSE));
    assertThat(run.getErrorKind()).isNull();
    assertThat(run.getStartedAt()).isAfterOrEqualTo(run.getCreatedAt());
    assertThat(run.getFinishedAt()).isAfterOrEqualTo(run.getStartedAt());
  }

  @Test
  void theEngineGetsTheStoredProfileAndTheUploadedBytes() throws Exception {
    submitAndWait();

    String storedProfile =
        jdbc.queryForObject(
            "SELECT profile::text FROM reference_profile WHERE id = ?", String.class, referenceId);
    assertThat(json.readTree(profileSent.get())).isEqualTo(json.readTree(storedProfile));
    assertThat(new String(profileSent.get(), StandardCharsets.UTF_8)).contains("\"first\"");
    assertThat(currentSent.get()).isEqualTo(CURRENT_CSV);
    assertThat(filenameSent.get()).isEqualTo("today.csv");
    assertThat(configSent.get()).isNull();
  }

  @Test
  void theRequestedConfigIsStoredAndSentToTheEngine() throws Exception {
    JsonNode config = json.readTree("{\"psi_threshold\": 0.25}");

    MonitoringRun run = awaitFinished(runs, submit(config).getId());

    assertThat(json.readTree(run.getRequestedConfig())).isEqualTo(config);
    assertThat(configSent.get()).isEqualTo(config);
  }

  @Test
  void aRunKeepsItsReferenceWhenANewerOneIsRegisteredWhileItWaits() throws Exception {
    CountDownLatch engineCalled = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              profileSent.set(invocation.getArgument(0));
              engineCalled.countDown();
              release.await(10, TimeUnit.SECONDS);
              return new DriftResult(json.readTree(DRIFT_RESPONSE));
            })
        .when(analytics)
        .drift(any(), any(), anyString(), any());
    MonitoringRun run = submit(null);
    assertThat(engineCalled.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(runs.get(run.getId()).getStatus()).isEqualTo(RunStatus.RUNNING);

    UUID newer = registerReference(references, analytics, modelId, "second");
    release.countDown();
    MonitoringRun finished = awaitFinished(runs, run.getId());

    assertThat(newer).isNotEqualTo(referenceId);
    assertThat(finished.getReferenceId()).isEqualTo(referenceId);
    assertThat(new String(profileSent.get(), StandardCharsets.UTF_8)).contains("\"first\"");
  }

  @Test
  void anEngineRejectionFailsTheRunWithTheEnginesProblem() throws Exception {
    engineThrows(
        new AnalyticsRejectedException(
            422,
            "schema-mismatch",
            "The current batch is missing monitored columns.",
            List.of(Map.of("column", "income", "message", "missing"))));

    MonitoringRun run = submitAndWait();

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("schema-mismatch");
    assertThat(run.getErrorMessage()).isEqualTo("The current batch is missing monitored columns.");
    assertThat(json.readTree(run.getErrorDetails()))
        .isEqualTo(json.readTree("[{\"column\": \"income\", \"message\": \"missing\"}]"));
    assertThat(run.getResult()).isNull();
    assertThat(run.getFinishedAt()).isNotNull();
  }

  @Test
  void anUnavailableEngineFailsTheRunWithoutRevealingItsAddress() throws Exception {
    engineThrows(
        new AnalyticsUnavailableException(
            "Analytics engine at http://10.0.0.7:8000 unavailable during drift", null));

    MonitoringRun run = submitAndWait();

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("analytics-unavailable");
    assertThat(run.getErrorMessage()).doesNotContain("10.0.0.7");
    assertThat(run.getErrorDetails()).isNull();
  }

  @Test
  void aContractViolationFailsTheRun() throws Exception {
    engineThrows(new AnalyticsProtocolException("bad JSON"));

    MonitoringRun run = submitAndWait();

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("analytics-protocol-error");
    assertThat(run.getErrorMessage()).doesNotContain("bad JSON");
  }

  @Test
  void aResponseWithoutASummaryIsAContractViolationNotASuccess() throws Exception {
    engineReturns("{\"engine_version\": \"0.1.0.dev0\", \"n_rows\": 2, \"features\": []}");

    MonitoringRun run = submitAndWait();

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("analytics-protocol-error");
    assertThat(run.getResult()).isNull();
  }

  @Test
  void anUnexpectedErrorFailsTheRunWithAGenericReason() throws Exception {
    engineThrows(new IllegalStateException("secret internal detail"));

    MonitoringRun run = submitAndWait();

    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getErrorKind()).isEqualTo("internal-error");
    assertThat(run.getErrorMessage()).doesNotContain("secret");
  }

  @Test
  void aFinishedRunIsNeverExecutedAgain() throws Exception {
    MonitoringRun run = submitAndWait();

    worker.execute(run.getId());

    verify(analytics, times(1)).drift(any(), any(), anyString(), any());
    assertThat(runs.get(run.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
  }

  @Test
  void aModelWithoutAReferenceCannotStartRunsAndNothingIsStored() throws Exception {
    UUID other = models.create("churn", TaskType.REGRESSION, null, null).getId();

    assertThatThrownBy(
            () -> runs.submit(other, "today.csv", new ByteArrayInputStream(CURRENT_CSV), null))
        .isInstanceOf(ReferenceRequiredException.class);
    assertThat(count("monitoring_run")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dataset WHERE model_id = ?", Integer.class, other))
        .isZero();
  }

  @Test
  void anUnknownModelIsRejected() {
    assertThatThrownBy(() ->
            runs.submit(UUID.randomUUID(), "x.csv", new ByteArrayInputStream(CURRENT_CSV), null))
        .isInstanceOf(ModelNotFoundException.class);
    verify(analytics, never()).drift(any(), any(), anyString(), any());
  }

  @Test
  void anUnknownRunIsNotFound() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> runs.get(id))
        .isInstanceOf(RunNotFoundException.class)
        .hasMessage("Monitoring run " + id + " does not exist");
  }

  @Test
  void recoveryFailsRunsLeftUnfinishedAndLeavesFinishedOnes() throws Exception {
    MonitoringRun done = submitAndWait();
    UUID pending = insertRun("PENDING");
    UUID running = insertRun("RUNNING");

    assertThat(recovery.recover()).isEqualTo(2);

    for (UUID id : List.of(pending, running)) {
      MonitoringRun run = runs.get(id);
      assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
      assertThat(run.getErrorKind()).isEqualTo("interrupted");
      assertThat(run.getErrorMessage()).isEqualTo(RunRecovery.MESSAGE);
      assertThat(run.getFinishedAt()).isNotNull();
    }
    assertThat(runs.get(done.getId()).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(recovery.recover()).isZero();
  }

  @Test
  void aRecoveredRunIsNotStartedByALateWorker() throws Exception {
    UUID pending = insertRun("PENDING");
    recovery.recover();

    worker.execute(pending);

    verify(analytics, never()).drift(any(), any(), anyString(), any());
    assertThat(runs.get(pending).getStatus()).isEqualTo(RunStatus.FAILED);
  }

  /** A run row as a previous process could have left it (no worker attached). */
  private UUID insertRun(String status) {
    UUID dataset = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO dataset (id, model_id, role, sha256, byte_size) VALUES (?, ?, 'CURRENT', ?, 1)",
        dataset,
        modelId,
        "b".repeat(64));
    UUID run = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO monitoring_run
            (id, model_id, reference_profile_id, current_dataset_id, status, created_at, started_at)
        VALUES (?, ?, ?, ?, ?, now() - interval '1 minute',
                CASE WHEN ? = 'RUNNING' THEN now() END)
        """,
        run,
        modelId,
        referenceId,
        dataset,
        status,
        status);
    return run;
  }
}
