package io.driftwatch.backend.dataset;

/** What an uploaded dataset is used for. */
public enum DatasetRole {
  /** Data the model was trained or validated on; the baseline for drift. */
  REFERENCE,
  /** A production batch compared against the reference. */
  CURRENT,
  /** Ground-truth labels for a batch, joined by record id. */
  LABELS
}
