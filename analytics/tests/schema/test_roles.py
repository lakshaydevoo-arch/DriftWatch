"""Prediction and target columns in schema inference and batch validation (design §5)."""

import pandas as pd
import pytest

from driftwatch_analytics.schema import (
    ColumnRole,
    ColumnSchema,
    ExclusionReason,
    IssueCode,
    LogicalType,
    TaskType,
    TypeSource,
    infer_schema,
    validate_batch,
)

BINARY = TaskType.BINARY_CLASSIFICATION
MULTICLASS = TaskType.MULTICLASS_CLASSIFICATION
REGRESSION = TaskType.REGRESSION


def _frame(**columns: list[str]) -> pd.DataFrame:
    return pd.DataFrame(columns, dtype="str")


def _prediction(
    values: list[str],
    task: TaskType,
    overrides: dict[str, LogicalType] | None = None,
) -> ColumnSchema:
    data = _frame(x=[str(i) for i in range(len(values))], pred=values)
    schema = infer_schema(data, overrides=overrides, task_type=task, prediction_column="pred")
    return schema.column("pred")


# --- prediction type by task


@pytest.mark.parametrize(
    ("values", "task", "expected", "integer_coded"),
    [
        # Probabilities: numeric in [0, 1] with a non-whole value.
        (["0.12", "0.87", "0.5", "1"], BINARY, LogicalType.NUMERIC, False),
        # 0/1 and yes/no labels are labels, not probabilities. They are
        # boolean tokens, and booleans use the categorical methods.
        (["0", "1", "1", "0"], BINARY, LogicalType.BOOLEAN, False),
        (["yes", "no", "no"], BINARY, LogicalType.BOOLEAN, False),
        (["0", "1", "2", "1"], MULTICLASS, LogicalType.CATEGORICAL, True),
        (["approved", "denied"], BINARY, LogicalType.CATEGORICAL, False),
        (["true", "false", "true"], BINARY, LogicalType.BOOLEAN, False),
        # Many integer classes would be NUMERIC by plain inference; as
        # predictions of a classifier they are still labels.
        ([str(i % 15) for i in range(60)], MULTICLASS, LogicalType.CATEGORICAL, True),
        (["cat", "dog", "bird"], MULTICLASS, LogicalType.CATEGORICAL, False),
        # Numbers above 1 are not probabilities.
        (["0.5", "2.5", "3"], BINARY, LogicalType.CATEGORICAL, False),
        # Regression is always numeric, even with few distinct integers.
        (["1", "2", "2", "3"], REGRESSION, LogicalType.NUMERIC, False),
        (["0.1", "0.2"], REGRESSION, LogicalType.NUMERIC, False),
    ],
)
def test_prediction_type_follows_the_task(
    values: list[str], task: TaskType, expected: LogicalType, integer_coded: bool
) -> None:
    column = _prediction(values, task)

    assert column.logical_type is expected
    assert column.integer_coded is integer_coded
    assert column.role is ColumnRole.PREDICTION
    assert column.source is TypeSource.TASK_TYPE
    assert column.monitored


def test_missing_values_do_not_stop_probability_detection() -> None:
    assert _prediction(["0.2", "", "NA", "0.9"], BINARY).logical_type is LogicalType.NUMERIC


def test_an_override_wins_over_the_task_rule() -> None:
    column = _prediction(["0.12", "0.87"], BINARY, overrides={"pred": LogicalType.CATEGORICAL})

    assert column.logical_type is LogicalType.CATEGORICAL
    assert column.source is TypeSource.OVERRIDE
    assert column.role is ColumnRole.PREDICTION


def test_a_prediction_is_never_excluded_by_the_identifier_heuristic() -> None:
    # 100 distinct text labels would normally look like an identifier.
    column = _prediction([f"class-{i}" for i in range(100)], MULTICLASS)

    assert column.excluded is None


# --- target column


def test_the_target_keeps_its_type_but_is_not_monitored() -> None:
    schema = infer_schema(_frame(x=["1.5", "2.5"], label=["0", "1"]), target_column="label")

    target = schema.column("label")
    assert target.role is ColumnRole.TARGET
    assert target.excluded is ExclusionReason.TARGET_COLUMN
    assert target.logical_type is LogicalType.BOOLEAN  # "0"/"1" are boolean tokens
    assert not target.monitored
    assert schema.column("x").role is ColumnRole.FEATURE


def test_roles_can_be_combined() -> None:
    schema = infer_schema(
        _frame(x=["1.5", "2.5"], score=["0.3", "0.6"], label=["0", "1"]),
        task_type=BINARY,
        prediction_column="score",
        target_column="label",
    )

    assert [(c.name, c.role) for c in schema.columns] == [
        ("x", ColumnRole.FEATURE),
        ("score", ColumnRole.PREDICTION),
        ("label", ColumnRole.TARGET),
    ]
    assert schema.with_role(ColumnRole.PREDICTION)[0].name == "score"


# --- invalid combinations


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"prediction_column": "nope", "task_type": BINARY}, r"not in the dataset: \['nope'\]"),
        ({"target_column": "nope"}, r"not in the dataset: \['nope'\]"),
        ({"prediction_column": "p"}, "requires task_type"),
        (
            {"prediction_column": "p", "target_column": "p", "task_type": BINARY},
            "must be different",
        ),
        (
            {"prediction_column": "p", "task_type": BINARY, "exclude": ["p"]},
            "cannot also be excluded",
        ),
        ({"target_column": "y", "exclude": ["y"]}, "cannot also be excluded"),
    ],
)
def test_contradictory_roles_are_rejected(kwargs: dict[str, object], message: str) -> None:
    with pytest.raises(ValueError, match=message):
        infer_schema(_frame(p=["0.1"], y=["1"]), **kwargs)  # type: ignore[arg-type]


# --- validation of current batches


def test_a_missing_target_in_a_current_batch_is_expected_and_silent() -> None:
    schema = infer_schema(_frame(x=["1.5", "2.5"], label=["0", "1"]), target_column="label")

    report = validate_batch(_frame(x=["3.5"]), schema)

    assert report.is_valid
    assert report.issues == ()


def test_a_missing_prediction_in_a_current_batch_is_an_error() -> None:
    schema = infer_schema(
        _frame(x=["1.5", "2.5"], score=["0.3", "0.6"]),
        task_type=BINARY,
        prediction_column="score",
    )

    report = validate_batch(_frame(x=["3.5"]), schema)

    assert not report.is_valid
    assert [(i.code, i.column) for i in report.issues] == [(IssueCode.MISSING_COLUMN, "score")]
