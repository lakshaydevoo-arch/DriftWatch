package io.driftwatch.backend.model;

/** Kind of prediction a model makes; decides which performance metrics apply (Milestone 3). */
public enum TaskType {
  BINARY_CLASSIFICATION,
  MULTICLASS_CLASSIFICATION,
  REGRESSION
}
