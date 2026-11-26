"""Performance metrics on joined labels (design §6).

Expected values are worked out by hand from confusion counts or residuals,
so the tests do not just repeat what scikit-learn computes.
"""

import math

import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.performance import (
    MetricsStatus,
    PerformanceConfig,
    PerformanceMetrics,
    compute_metrics,
    join_labels,
)
from driftwatch_analytics.schema import DatasetSchema, TaskType, infer_schema

ANY = PerformanceConfig(min_evaluated=1)


def _frame(**columns: list[str]) -> pd.DataFrame:
    return pd.DataFrame(columns, dtype="str")


def _schema(task: TaskType, prediction: list[str], target: list[str]) -> DatasetSchema:
    ref = _frame(id=[f"r{i}" for i in range(len(target))], pred=prediction, y=target)
    return infer_schema(
        ref, task_type=task, prediction_column="pred", target_column="y", record_id_column="id"
    )


def _metrics(
    schema: DatasetSchema,
    predictions: list[str],
    labels: list[str],
    config: PerformanceConfig = ANY,
) -> PerformanceMetrics:
    ids = [f"c{i}" for i in range(len(predictions))]
    current = _frame(id=ids, pred=predictions)
    joined = join_labels(current, _frame(id=ids, y=labels), schema).joined
    return compute_metrics(current, joined, schema, config)


BINARY_PROB = _schema(TaskType.BINARY_CLASSIFICATION, ["0.1", "0.9"], ["0", "1"])


def test_binary_probabilities_hand_computed() -> None:
    # threshold 0.5 -> predicted positive: rows 0, 1, 2 ; actual positive: rows 0, 1, 3
    # TP = 2 (0, 1), FP = 1 (2), FN = 1 (3), TN = 1 (4)
    scores = ["0.9", "0.6", "0.7", "0.2", "0.1"]
    labels = ["1", "1", "0", "1", "0"]

    result = _metrics(BINARY_PROB, scores, labels)

    assert result.status is MetricsStatus.EVALUATED
    assert result.positive_label == "true"
    m = result.metrics
    assert m["accuracy"] == pytest.approx(3 / 5)
    assert m["precision"] == pytest.approx(2 / 3)
    assert m["recall"] == pytest.approx(2 / 3)
    assert m["f1"] == pytest.approx(2 / 3)
    # Positives {0.9, 0.6, 0.2} vs negatives {0.7, 0.1}: of 6 pairs, positive
    # ranks higher in 4 (0.9>0.7, 0.9>0.1, 0.6>0.1, 0.2>0.1) -> AUC = 4/6.
    assert m["roc_auc"] == pytest.approx(4 / 6)


def test_the_probability_threshold_is_configurable() -> None:
    config = PerformanceConfig(min_evaluated=1, probability_threshold=0.65)

    result = _metrics(BINARY_PROB, ["0.6", "0.7"], ["1", "0"], config)

    # 0.6 -> negative (wrong), 0.7 -> positive (wrong)
    assert result.metrics["accuracy"] == 0.0


def test_binary_boolean_labels_have_no_roc_auc() -> None:
    schema = _schema(TaskType.BINARY_CLASSIFICATION, ["yes", "no"], ["yes", "no"])

    result = _metrics(schema, ["yes", "yes", "no", "no"], ["yes", "no", "no", "yes"])

    assert result.metrics["accuracy"] == 0.5
    assert result.metrics["precision"] == 0.5
    assert result.metrics["recall"] == 0.5
    assert "roc_auc" not in result.metrics
    assert any("roc_auc needs probabilities" in n for n in result.notes)


def test_text_labels_without_a_positive_label_report_accuracy_only() -> None:
    schema = _schema(TaskType.BINARY_CLASSIFICATION, ["approve", "deny"], ["approve", "deny"])

    result = _metrics(schema, ["approve", "deny", "deny"], ["approve", "approve", "deny"])

    assert result.status is MetricsStatus.EVALUATED
    assert result.positive_label is None
    assert result.metrics["accuracy"] == pytest.approx(2 / 3)
    assert result.metrics["precision"] is None
    assert result.metrics["recall"] is None
    assert result.metrics["f1"] is None
    assert any("set positive_label" in n for n in result.notes)


def test_text_labels_with_a_positive_label() -> None:
    schema = _schema(TaskType.BINARY_CLASSIFICATION, ["approve", "deny"], ["approve", "deny"])
    config = PerformanceConfig(min_evaluated=1, positive_label="deny")

    # positive = deny: TP = 1 (row 2), FN = 1 (row 1), FP = 0
    result = _metrics(schema, ["approve", "approve", "deny"], ["approve", "deny", "deny"], config)

    assert result.positive_label == "deny"
    assert result.metrics["precision"] == 1.0
    assert result.metrics["recall"] == 0.5
    assert result.metrics["f1"] == pytest.approx(2 / 3)


def test_probabilities_with_text_labels_need_a_positive_label() -> None:
    schema = _schema(TaskType.BINARY_CLASSIFICATION, ["0.2", "0.8"], ["approve", "deny"])

    result = _metrics(schema, ["0.2", "0.8"], ["approve", "deny"])

    assert result.status is MetricsStatus.UNAVAILABLE
    assert result.reason is not None and "positive_label" in result.reason


def test_undefined_metrics_are_none_not_zero() -> None:
    # Only negatives in the labels and nothing predicted positive.
    result = _metrics(BINARY_PROB, ["0.1", "0.2", "0.3"], ["0", "0", "0"])

    assert result.metrics["accuracy"] == 1.0
    assert result.metrics["precision"] is None  # no predicted positives
    assert result.metrics["recall"] is None  # no actual positives
    assert result.metrics["roc_auc"] is None
    assert any("only one class" in n for n in result.notes)


def test_regression_hand_computed() -> None:
    schema = _schema(TaskType.REGRESSION, ["1.5", "2.5"], ["1.0", "3.0"])
    # residuals: 1, -1, 2, 0 ; labels 1, 3, 2, 6 (mean 3, SS_tot = 4+0+1+9 = 14)
    result = _metrics(schema, ["2", "2", "4", "6"], ["1", "3", "2", "6"])

    m = result.metrics
    assert m["mae"] == pytest.approx(4 / 4)
    assert m["rmse"] == pytest.approx(math.sqrt(6 / 4))
    assert m["r2"] == pytest.approx(1 - 6 / 14)


def test_r2_with_constant_labels_is_none() -> None:
    schema = _schema(TaskType.REGRESSION, ["1.5", "2.5"], ["1.0", "3.0"])

    result = _metrics(schema, ["1", "2"], ["2", "2"])

    assert result.metrics["r2"] is None
    assert result.metrics["mae"] == 0.5


def test_multiclass_hand_computed() -> None:
    schema = _schema(TaskType.MULTICLASS_CLASSIFICATION, ["cat", "dog"], ["cat", "dog"])
    truth = ["cat", "cat", "dog", "bird"]
    predicted = ["cat", "dog", "dog", "dog"]
    # per class F1: cat (P 1, R 1/2) = 2/3 ; dog (P 1/3, R 1) = 1/2 ; bird (P -, R 0) = 0
    result = _metrics(schema, predicted, truth)

    m = result.metrics
    assert m["accuracy"] == 0.5
    assert m["macro_f1"] == pytest.approx((2 / 3 + 1 / 2 + 0) / 3)
    # supports: cat 2, dog 1, bird 1
    assert m["weighted_f1"] == pytest.approx((2 * 2 / 3 + 1 * 1 / 2 + 0) / 4)


def test_missing_and_invalid_predictions_are_excluded_and_counted() -> None:
    result = _metrics(BINARY_PROB, ["0.9", "", "abc", "0.1"], ["1", "1", "0", "0"])

    assert (result.n_evaluated, result.n_missing_predictions, result.n_invalid_predictions) == (
        2,
        1,
        1,
    )
    assert result.metrics["accuracy"] == 1.0


def test_fewer_than_min_evaluated_rows_report_no_metrics() -> None:
    result = _metrics(BINARY_PROB, ["0.9"] * 29, ["1"] * 29, PerformanceConfig())

    assert result.status is MetricsStatus.INSUFFICIENT_DATA
    assert result.metrics == {}
    assert result.reason is not None and "at least 30" in result.reason


def test_a_profile_without_task_type_has_no_metrics() -> None:
    schema = infer_schema(
        _frame(id=["r1"], pred=["0.5"], y=["1"]), target_column="y", record_id_column="id"
    )

    result = _metrics(schema, ["0.5"], ["1"])

    assert result.status is MetricsStatus.UNAVAILABLE


def test_a_batch_without_the_prediction_column_has_no_metrics() -> None:
    current = _frame(id=["c0"])
    joined = join_labels(current, _frame(id=["c0"], y=["1"]), BINARY_PROB).joined

    result = compute_metrics(current, joined, BINARY_PROB, ANY)

    assert result.status is MetricsStatus.UNAVAILABLE
    assert result.reason is not None and "'pred'" in result.reason


def test_seeded_batch_matches_an_independent_computation() -> None:
    rng = np.random.default_rng(3)
    n = 2_000
    scores = rng.random(n)
    labels = (rng.random(n) < scores).astype(int)  # calibrated scores
    result = _metrics(
        BINARY_PROB,
        [f"{s:.6f}" for s in scores],
        [str(v) for v in labels],
        PerformanceConfig(),
    )

    predicted = scores >= 0.5
    actual = labels == 1
    tp = int((predicted & actual).sum())
    fp = int((predicted & ~actual).sum())
    fn = int((~predicted & actual).sum())
    assert result.metrics["accuracy"] == pytest.approx(float((predicted == actual).mean()))
    assert result.metrics["precision"] == pytest.approx(tp / (tp + fp))
    assert result.metrics["recall"] == pytest.approx(tp / (tp + fn))
    # Calibrated uniform scores: positives have density 2s, negatives 2(1 - s),
    # so AUC = P(s+ > s-) = integral of 2x * (2x - x^2) dx over [0, 1] = 5/6.
    # A check that the scores (not only the thresholded classes) were used.
    assert result.metrics["roc_auc"] == pytest.approx(5 / 6, abs=0.02)
