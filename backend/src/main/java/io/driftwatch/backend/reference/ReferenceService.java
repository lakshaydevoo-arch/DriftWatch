package io.driftwatch.backend.reference;

import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsProtocolException;
import io.driftwatch.backend.analytics.ProfileResult;
import io.driftwatch.backend.dataset.Dataset;
import io.driftwatch.backend.dataset.DatasetRole;
import io.driftwatch.backend.dataset.DatasetService;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.MonitoredModel;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Registers reference datasets: store the file, have the engine profile it, persist the result.
 *
 * <p>Steps 1-3 run without a database transaction (they can take minutes for a large file). Step 4
 * writes the dataset and its profile in one transaction, so the database only ever holds
 * successfully profiled references. If the engine rejects the file, nothing is written; the file
 * stays in the content-addressed store as a harmless, reusable blob.
 */
@Service
public class ReferenceService {

  /** Profile options that come from the registered model, never from the client. */
  private static final List<String> MODEL_FIELDS =
      List.of("task_type", "prediction_column", "target_column");

  private final ModelService models;
  private final DatasetService datasets;
  private final AnalyticsClient analytics;
  private final ReferenceRepository references;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  ReferenceService(
      ModelService models,
      DatasetService datasets,
      AnalyticsClient analytics,
      ReferenceRepository references,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.models = models;
    this.datasets = datasets;
    this.analytics = analytics;
    this.references = references;
    this.transactions = transactions;
    this.json = json;
  }

  /** A registered reference together with its dataset. */
  public record Registered(Reference reference, Dataset dataset) {}

  /**
   * Registers new reference data for a model; it becomes the model's current reference.
   *
   * @param options JSON {@code ProfileOptions} for the engine, or {@code null}
   * @throws io.driftwatch.backend.model.ModelNotFoundException if the model does not exist
   * @throws io.driftwatch.backend.analytics.AnalyticsException if profiling fails
   */
  public Registered register(UUID modelId, String filename, InputStream content, JsonNode options)
      throws IOException {
    // 0. The model's task and columns become profile options (fails before the upload is stored).
    ObjectNode profileOptions = withModelColumns(models.get(modelId), options);
    // 1-2. Check the model and store the file (DatasetService does both).
    DatasetService.StoredUpload upload = datasets.storeFile(modelId, filename, content);
    // 3. Profile it.
    ProfileResult result =
        analytics.profile(datasets.resource(upload), safeName(filename), profileOptions);
    JsonNode profile = result.profile();
    long rowCount = requireLong(profile, "n_rows");
    String engineVersion = requireText(profile, "engine_version");
    // 4. Persist the dataset and its profile together.
    return transactions.execute(status -> {
      Dataset dataset = datasets.record(upload, DatasetRole.REFERENCE);
      Reference reference =
          references.saveAndFlush(
              new Reference(
                  modelId,
                  dataset.getId(),
                  engineVersion,
                  rowCount,
                  json.writeValueAsString(profile),
                  json.writeValueAsString(result.issues())));
      return new Registered(reference, dataset);
    });
  }

  /** The model's current reference. */
  @Transactional(readOnly = true)
  public Reference current(UUID modelId) {
    models.get(modelId);
    return references
        .findFirstByModelIdOrderByCreatedAtDescIdDesc(modelId)
        .orElseThrow(() -> new ReferenceNotFoundException(modelId));
  }

  /**
   * The id of the model's current reference, without loading its profile.
   *
   * @throws io.driftwatch.backend.model.ModelNotFoundException if the model does not exist
   * @throws ReferenceNotFoundException if the model has no reference yet
   */
  @Transactional(readOnly = true)
  public UUID currentId(UUID modelId) {
    models.get(modelId);
    return references
        .findFirstByModelIdOrderByCreatedAtDescIdDesc(modelId, ReferenceRepository.IdOnly.class)
        .map(ReferenceRepository.IdOnly::getId)
        .orElseThrow(() -> new ReferenceNotFoundException(modelId));
  }

  /** A reference by id (for example the one a monitoring run was created with). */
  @Transactional(readOnly = true)
  public Reference get(UUID id) {
    return references
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("reference " + id + " does not exist"));
  }

  /** The dataset behind a reference. */
  @Transactional(readOnly = true)
  public Dataset datasetOf(Reference reference) {
    return datasets.get(reference.getDatasetId());
  }

  /**
   * The client's options plus the model's task type and its prediction and target columns, so the
   * engine monitors the prediction as prediction drift and leaves the label out of drift checks.
   * These come only from the model: a client setting them in {@code options} could profile a
   * reference that contradicts the registered model.
   */
  private ObjectNode withModelColumns(MonitoredModel model, JsonNode options) {
    ObjectNode merged =
        options == null ? json.createObjectNode() : ((ObjectNode) options).deepCopy();
    for (String field : MODEL_FIELDS) {
      if (merged.has(field)) {
        throw new InvalidOptionsException(
            "'options." + field + "' is set from the model and cannot be given here");
      }
    }
    merged.put("task_type", model.getTaskType().name().toLowerCase(Locale.ROOT));
    if (model.getPredictionColumn() != null) {
      merged.put("prediction_column", model.getPredictionColumn());
    }
    if (model.getTargetColumn() != null) {
      merged.put("target_column", model.getTargetColumn());
    }
    return merged;
  }

  /** The engine only uses the name in messages; fall back when the client sent none. */
  private static String safeName(String filename) {
    return filename == null ? "reference.csv" : filename;
  }

  private static long requireLong(JsonNode profile, String field) {
    JsonNode value = profile.get(field);
    if (value == null || !value.canConvertToLong() || value.asLong() < 1) {
      throw new AnalyticsProtocolException("profile has no valid '" + field + "'");
    }
    return value.asLong();
  }

  private static String requireText(JsonNode profile, String field) {
    JsonNode value = profile.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw new AnalyticsProtocolException("profile has no valid '" + field + "'");
    }
    return value.asString();
  }
}
