package io.driftwatch.backend.run;

import io.driftwatch.backend.dataset.Dataset;
import io.driftwatch.backend.dataset.DatasetRole;
import io.driftwatch.backend.dataset.DatasetService;
import io.driftwatch.backend.reference.ReferenceNotFoundException;
import io.driftwatch.backend.reference.ReferenceService;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates monitoring runs and reads them back. The comparison itself happens later, on a worker
 * ({@link RunWorker}).
 *
 * <p>{@link #submit} steps:
 *
 * <ol>
 *   <li>reserve a place in the bounded executor (refuse at once if full: nothing is stored),
 *   <li>find the model's current reference (the run is pinned to it, even if a newer reference is
 *       registered while the run waits),
 *   <li>store the file, with no transaction open,
 *   <li>insert the CURRENT dataset and the PENDING run in one transaction,
 *   <li>after the commit, hand the run to a worker, so the worker always finds the row.
 * </ol>
 */
@Service
public class RunService {

  private final ReferenceService references;
  private final DatasetService datasets;
  private final MonitoringRunRepository runs;
  private final RunExecutor executor;
  private final RunWorker worker;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  RunService(
      ReferenceService references,
      DatasetService datasets,
      MonitoringRunRepository runs,
      RunExecutor executor,
      RunWorker worker,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.references = references;
    this.datasets = datasets;
    this.runs = runs;
    this.executor = executor;
    this.worker = worker;
    this.transactions = transactions;
    this.json = json;
  }

  /**
   * Stores a current batch and queues a run comparing it with the model's current reference.
   *
   * @param config JSON {@code DriftConfig} for the engine, or {@code null} for its defaults. Its
   *     content is validated by the engine when the run executes.
   * @return the new run, PENDING
   * @throws RunCapacityExceededException if too many runs are in progress
   * @throws io.driftwatch.backend.model.ModelNotFoundException if the model does not exist
   * @throws ReferenceRequiredException if the model has no reference yet
   */
  public MonitoringRun submit(UUID modelId, String filename, InputStream content, JsonNode config)
      throws IOException {
    try (RunExecutor.Slot slot = executor.reserve()) {
      UUID referenceId = currentReference(modelId);
      DatasetService.StoredUpload upload = datasets.storeFile(modelId, filename, content);
      String requestedConfig = config == null ? null : json.writeValueAsString(config);
      MonitoringRun run =
          transactions.execute(status -> {
            Dataset dataset = datasets.record(upload, DatasetRole.CURRENT);
            return runs.saveAndFlush(
                new MonitoringRun(modelId, referenceId, dataset.getId(), requestedConfig));
          });
      UUID runId = run.getId();
      slot.submit(() -> worker.execute(runId));
      return run;
    }
  }

  /**
   * A run by id.
   *
   * @throws RunNotFoundException if there is no such run
   */
  @Transactional(readOnly = true)
  public MonitoringRun get(UUID runId) {
    return runs.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
  }

  /** The dataset a run compares. */
  @Transactional(readOnly = true)
  public Dataset currentDatasetOf(MonitoringRun run) {
    return datasets.get(run.getCurrentDatasetId());
  }

  private UUID currentReference(UUID modelId) {
    try {
      return references.currentId(modelId);
    } catch (ReferenceNotFoundException e) {
      throw new ReferenceRequiredException(modelId);
    }
  }
}
