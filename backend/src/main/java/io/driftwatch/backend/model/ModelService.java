package io.driftwatch.backend.model;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers and looks up monitored models. */
@Service
public class ModelService {

  /** Name of the unique constraint in Flyway V1. */
  static final String NAME_CONSTRAINT = "uq_monitored_model_name";

  private final MonitoredModelRepository repository;

  ModelService(MonitoredModelRepository repository) {
    this.repository = repository;
  }

  /**
   * Creates a model.
   *
   * <p>Uniqueness is enforced by the database constraint, not by checking first: a
   * check-then-insert would let two concurrent requests both pass the check.
   *
   * @throws ModelNameTakenException if the name is already used
   */
  @Transactional
  public MonitoredModel create(
      String name, TaskType taskType, String targetColumn, String predictionColumn) {
    try {
      // Flush now so a constraint violation surfaces here, inside the try.
      return repository.saveAndFlush(
          new MonitoredModel(name, taskType, targetColumn, predictionColumn));
    } catch (DataIntegrityViolationException e) {
      if (isNameConflict(e)) {
        throw new ModelNameTakenException(name, e);
      }
      throw e;
    }
  }

  @Transactional(readOnly = true)
  public MonitoredModel get(UUID id) {
    return repository.findById(id).orElseThrow(() -> new ModelNotFoundException(id));
  }

  /** Models sorted by name (then id, so pages are stable). */
  @Transactional(readOnly = true)
  public Page<MonitoredModel> list(int page, int size) {
    return repository.findAll(
        PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
  }

  private static boolean isNameConflict(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null && message.contains(NAME_CONSTRAINT)) {
        return true;
      }
    }
    return false;
  }
}
