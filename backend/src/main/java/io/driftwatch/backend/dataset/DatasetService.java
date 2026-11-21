package io.driftwatch.backend.dataset;

import io.driftwatch.backend.model.ModelService;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores uploaded dataset files and records them.
 *
 * <p>Order of operations in {@link #store}:
 *
 * <ol>
 *   <li>check that the model exists (a short read),
 *   <li>stream the file into the store, <em>with no database transaction open</em>: holding a
 *       connection for the length of a large upload would exhaust the pool under load,
 *   <li>insert the row in a short transaction.
 * </ol>
 *
 * If step 3 fails (for example the model was deleted in between, caught by the foreign key), the
 * stored file stays as an unreferenced but harmless blob: it is content-addressed, so a later
 * upload of the same bytes reuses it. Removing unreferenced files is left to a future clean-up job.
 */
@Service
public class DatasetService {

  static final int MAX_FILENAME_LENGTH = 255;
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

  private final ModelService models;
  private final ContentAddressedFileStore files;
  private final DatasetRepository repository;
  private final TransactionTemplate transactions;

  DatasetService(
      ModelService models,
      ContentAddressedFileStore files,
      DatasetRepository repository,
      TransactionTemplate transactions) {
    this.models = models;
    this.files = files;
    this.repository = repository;
    this.transactions = transactions;
  }

  /**
   * Stores an uploaded file for a model.
   *
   * @throws io.driftwatch.backend.model.ModelNotFoundException if the model does not exist
   * @throws UploadTooLargeException if the file exceeds the configured limit
   * @throws EmptyUploadException if the file is empty
   * @throws InvalidUploadException if the file name is invalid
   */
  public Dataset store(UUID modelId, DatasetRole role, String originalFilename, InputStream content)
      throws IOException {
    StoredUpload upload = storeFile(modelId, originalFilename, content);
    return transactions.execute(status -> insert(upload, role));
  }

  /** A file that is in the store but not yet recorded as a dataset. */
  public record StoredUpload(UUID modelId, String sha256, long byteSize, String originalFilename) {}

  /**
   * Steps 1 and 2 of {@link #store}: validate, check the model, store the file. No transaction is
   * opened. Use with {@link #record} when work (such as profiling) must happen in between.
   */
  public StoredUpload storeFile(UUID modelId, String originalFilename, InputStream content)
      throws IOException {
    validateFilename(originalFilename);
    models.get(modelId);
    ContentAddressedFileStore.StoredFile stored = files.store(content);
    return new StoredUpload(modelId, stored.sha256(), stored.byteSize(), originalFilename);
  }

  /**
   * Step 3 of {@link #store}: records a stored file. Must run inside the caller's transaction, so
   * the dataset row commits or rolls back together with whatever the caller writes.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Dataset record(StoredUpload upload, DatasetRole role) {
    return insert(upload, role);
  }

  private Dataset insert(StoredUpload upload, DatasetRole role) {
    return repository.saveAndFlush(
        new Dataset(
            upload.modelId(), role, upload.sha256(), upload.byteSize(), upload.originalFilename()));
  }

  /** The stored content of an upload, e.g. to send it to the analytics engine. */
  public Resource resource(StoredUpload upload) {
    return files.resource(upload.sha256());
  }

  /** A dataset by id. */
  @Transactional(readOnly = true)
  public Dataset get(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("dataset " + id + " does not exist"));
  }

  /** All datasets of a model, oldest first. */
  public List<Dataset> forModel(UUID modelId) {
    return repository.findByModelIdOrderByCreatedAtAscIdAsc(modelId);
  }

  /** Opens the stored content of a dataset. */
  public InputStream open(Dataset dataset) throws IOException {
    return files.open(dataset.getSha256());
  }

  private static void validateFilename(String filename) {
    if (filename == null) {
      return;
    }
    if (filename.isBlank() || filename.length() > MAX_FILENAME_LENGTH) {
      throw new InvalidUploadException(
          "file name must be 1-" + MAX_FILENAME_LENGTH + " characters and not blank");
    }
    if (CONTROL_CHARACTERS.matcher(filename).find()) {
      throw new InvalidUploadException("file name must not contain control characters");
    }
  }
}
