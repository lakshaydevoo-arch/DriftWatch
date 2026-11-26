"""Performance metrics on rows whose labels were joined (design §6).

- Binary classification: accuracy; precision, recall and F1 for the
  positive class; ROC AUC when the prediction is a probability.
- Multiclass classification: accuracy, macro F1, weighted F1.
- Regression: MAE, RMSE, R².

Policies:

- **Positive class (binary):** for boolean labels (``0/1``, ``yes/no``,
  ``true/false``) the true value is positive. For other labels
  ``positive_label`` must be configured; without it only accuracy is
  reported, and precision, recall and F1 are ``None`` with a note. The
  positive class is never guessed (chosen by the maintainer).
- **Probabilities (binary):** a numeric prediction is the probability of the
  positive class; the predicted class is ``score >= probability_threshold``
  (default 0.5).
- Rows whose prediction is missing or does not parse are excluded and
  counted. Below ``min_evaluated`` rows (default 30, as for drift) no metric
  is reported: a metric on a handful of rows is noise.
- A metric that is mathematically undefined for the data (ROC AUC with one
  class present, precision with no predicted positives, R² with constant
  labels) is ``None``, never 0.
"""

import math
from dataclasses import dataclass
from enum import StrEnum

import numpy as np
import pandas as pd
from pydantic import BaseModel, ConfigDict, Field
from sklearn.metrics import (
    accuracy_score,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    precision_score,
    r2_score,
    recall_score,
    roc_auc_score,
)

from driftwatch_analytics.schema import ColumnRole, DatasetSchema, LogicalType, TaskType
from driftwatch_analytics.schema.parsing import parse_column


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class PerformanceConfig(_Frozen):
    min_evaluated: int = Field(default=30, ge=1)
    probability_threshold: float = Field(default=0.5, gt=0, lt=1)
    # Required for binary classification with non-boolean labels.
    positive_label: str | None = None


class MetricsStatus(StrEnum):
    EVALUATED = "evaluated"
    # Fewer than min_evaluated rows with both a label and a prediction.
    INSUFFICIENT_DATA = "insufficient_data"
    # The profile or data cannot support metrics; ``reason`` says why.
    UNAVAILABLE = "unavailable"


class PerformanceMetrics(_Frozen):
    status: MetricsStatus
    task_type: TaskType | None
    reason: str | None = None
    n_evaluated: int = Field(ge=0, description="Joined rows with a valid prediction")
    n_missing_predictions: int = Field(ge=0)
    n_invalid_predictions: int = Field(ge=0)
    positive_label: str | None = None
    # Metric name -> value; None where undefined for this data.
    metrics: dict[str, float | None] = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list)


@dataclass(frozen=True)
class _Counts:
    evaluated: int = 0
    missing: int = 0
    invalid: int = 0


def _result(
    status: MetricsStatus,
    task: TaskType | None,
    counts: _Counts,
    *,
    reason: str | None = None,
    positive_label: str | None = None,
    metrics: dict[str, float | None] | None = None,
    notes: list[str] | None = None,
) -> PerformanceMetrics:
    return PerformanceMetrics(
        status=status,
        task_type=task,
        reason=reason,
        n_evaluated=counts.evaluated,
        n_missing_predictions=counts.missing,
        n_invalid_predictions=counts.invalid,
        positive_label=positive_label,
        metrics=metrics or {},
        notes=notes or [],
    )


def _unavailable(task: TaskType | None, reason: str) -> PerformanceMetrics:
    return _result(MetricsStatus.UNAVAILABLE, task, _Counts(), reason=reason)


def compute_metrics(
    current: pd.DataFrame,
    joined: pd.DataFrame,
    schema: DatasetSchema,
    config: PerformanceConfig | None = None,
) -> PerformanceMetrics:
    """Metrics for the ``joined`` rows (from ``join_labels``) of the ``current`` batch."""
    config = config or PerformanceConfig()
    predictions = schema.with_role(ColumnRole.PREDICTION)
    targets = schema.with_role(ColumnRole.TARGET)
    task = schema.task_type
    if task is None or not predictions or not targets:
        return _unavailable(
            task, "the reference profile has no task type, prediction column or target column"
        )
    prediction, target = predictions[0], targets[0]
    if prediction.name not in current.columns:
        return _unavailable(task, f"the batch has no prediction column {prediction.name!r}")

    parsed = parse_column(
        current[prediction.name], prediction.logical_type, integer_coded=prediction.integer_coded
    )
    rows = joined["row"].to_numpy(dtype=int)
    missing = parsed.missing[rows]
    invalid = parsed.invalid[rows]
    usable = ~(missing | invalid)
    y_pred = parsed.values.iloc[rows][usable].reset_index(drop=True)
    y_true = joined["label"][usable].reset_index(drop=True)
    counts = _Counts(int(usable.sum()), int(missing.sum()), int(invalid.sum()))
    if counts.evaluated < config.min_evaluated:
        return _result(
            MetricsStatus.INSUFFICIENT_DATA,
            task,
            counts,
            reason=(
                f"{counts.evaluated} rows have both a label and a prediction; "
                f"at least {config.min_evaluated} are needed"
            ),
        )

    match task:
        case TaskType.REGRESSION:
            return _regression(y_true, y_pred, target.logical_type, prediction.logical_type, counts)
        case TaskType.BINARY_CLASSIFICATION:
            return _binary(
                y_true, y_pred, target.logical_type, prediction.logical_type, config, counts
            )
        case TaskType.MULTICLASS_CLASSIFICATION:
            return _multiclass(y_true, y_pred, target.logical_type, prediction.logical_type, counts)


def _labels(values: pd.Series, logical_type: LogicalType) -> np.ndarray:
    """Class labels as strings, so a boolean ``True`` and the text ``"true"`` compare equal."""
    if logical_type is LogicalType.BOOLEAN:
        return np.where(values.astype(bool).to_numpy(), "true", "false")
    return values.astype(str).to_numpy()


def _defined(value: float) -> float | None:
    return None if math.isnan(value) else float(value)


def _regression(
    y_true: pd.Series,
    y_pred: pd.Series,
    target_type: LogicalType,
    prediction_type: LogicalType,
    counts: _Counts,
) -> PerformanceMetrics:
    task = TaskType.REGRESSION
    if target_type is not LogicalType.NUMERIC or prediction_type is not LogicalType.NUMERIC:
        return _unavailable(task, "regression metrics need a numeric target and prediction")
    truth = y_true.to_numpy(dtype=float)
    predicted = y_pred.to_numpy(dtype=float)
    notes = []
    r2: float | None = None
    if np.ptp(truth) > 0:
        r2 = float(r2_score(truth, predicted))
    else:
        notes.append("r2 is undefined: every label has the same value")
    return _result(
        MetricsStatus.EVALUATED,
        task,
        counts,
        metrics={
            "mae": float(mean_absolute_error(truth, predicted)),
            "rmse": math.sqrt(float(mean_squared_error(truth, predicted))),
            "r2": r2,
        },
        notes=notes,
    )


def _binary(
    y_true: pd.Series,
    y_pred: pd.Series,
    target_type: LogicalType,
    prediction_type: LogicalType,
    config: PerformanceConfig,
    counts: _Counts,
) -> PerformanceMetrics:
    task = TaskType.BINARY_CLASSIFICATION
    if target_type is LogicalType.NUMERIC:
        return _unavailable(task, "binary classification needs a class-label target, not numeric")
    truth = _labels(y_true, target_type)
    positive = config.positive_label
    if positive is None and target_type is LogicalType.BOOLEAN:
        positive = "true"
    probabilities = prediction_type is LogicalType.NUMERIC
    if probabilities and positive is None:
        return _unavailable(
            task,
            "the prediction is a probability but the labels are not boolean; "
            "set positive_label to say which class it is the probability of",
        )

    notes: list[str] = []
    metrics: dict[str, float | None] = {}
    if positive is None:
        # Class labels compared as they are; nothing depends on a positive class.
        metrics["accuracy"] = float(accuracy_score(truth, _labels(y_pred, prediction_type)))
        metrics.update(precision=None, recall=None, f1=None)
        notes.append(
            "precision, recall and f1 need the positive class: set positive_label "
            "(labels are not boolean)"
        )
        notes.append("roc_auc needs probabilities; the prediction is a class label")
        return _result(MetricsStatus.EVALUATED, task, counts, metrics=metrics, notes=notes)

    actual = truth == positive
    if probabilities:
        scores = y_pred.to_numpy(dtype=float)
        predicted = scores >= config.probability_threshold
    else:
        predicted = _labels(y_pred, prediction_type) == positive
    metrics["accuracy"] = float(np.mean(predicted == actual))
    for name, metric in (
        ("precision", precision_score),
        ("recall", recall_score),
        ("f1", f1_score),
    ):
        metrics[name] = _defined(float(metric(actual, predicted, zero_division=np.nan)))
    both_classes = bool(actual.any() and not actual.all())
    if not both_classes:
        notes.append("only one class is present in the labels")
    if probabilities:
        metrics["roc_auc"] = float(roc_auc_score(actual, scores)) if both_classes else None
        if not both_classes:
            notes.append("roc_auc is undefined: only one class is present in the labels")
    else:
        notes.append("roc_auc needs probabilities; the prediction is a class label")
    return _result(
        MetricsStatus.EVALUATED,
        task,
        counts,
        positive_label=positive,
        metrics=metrics,
        notes=notes,
    )


def _multiclass(
    y_true: pd.Series,
    y_pred: pd.Series,
    target_type: LogicalType,
    prediction_type: LogicalType,
    counts: _Counts,
) -> PerformanceMetrics:
    task = TaskType.MULTICLASS_CLASSIFICATION
    if prediction_type is LogicalType.NUMERIC:
        return _unavailable(
            task, "multiclass metrics need predicted class labels, not a single numeric score"
        )
    if target_type is LogicalType.NUMERIC:
        return _unavailable(task, "multiclass metrics need a class-label target, not numeric")
    truth = _labels(y_true, target_type)
    predicted = _labels(y_pred, prediction_type)
    # Per-class F1 uses zero_division=0: every class in the union of labels and
    # predictions was either missed (recall 0) or wrongly predicted
    # (precision 0), so 0 is the correct F1 for it, not an undefined value.
    return _result(
        MetricsStatus.EVALUATED,
        task,
        counts,
        metrics={
            "accuracy": float(accuracy_score(truth, predicted)),
            "macro_f1": float(f1_score(truth, predicted, average="macro", zero_division=0)),
            "weighted_f1": float(f1_score(truth, predicted, average="weighted", zero_division=0)),
        },
    )
