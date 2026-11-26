"""Joining labels to a batch by record id (design §6)."""

import pandas as pd
import pytest

from driftwatch_analytics.performance import JoinError, join_labels
from driftwatch_analytics.schema import ColumnRole, DatasetSchema, TaskType, infer_schema


def _frame(**columns: list[str]) -> pd.DataFrame:
    return pd.DataFrame(columns, dtype="str")


def _schema(target: list[str] | None = None) -> DatasetSchema:
    """Reference: id, a feature, a probability score and a label (``target`` values)."""
    labels = target or ["0", "1", "1", "0"]
    reference = _frame(
        id=[f"r{i}" for i in range(len(labels))],
        x=[str(i + 0.5) for i in range(len(labels))],
        score=["0.2", "0.7", "0.9", "0.1"][: len(labels)],
        label=labels,
    )
    return infer_schema(
        reference,
        task_type=TaskType.BINARY_CLASSIFICATION,
        prediction_column="score",
        target_column="label",
        record_id_column="id",
    )


def test_the_record_id_column_has_its_own_role_and_is_not_monitored() -> None:
    column = _schema().column("id")

    assert column.role is ColumnRole.RECORD_ID
    assert column.excluded is not None
    assert column.excluded.value == "record_id"


def test_every_row_joins() -> None:
    current = _frame(id=["a", "b", "c"], score=["0.1", "0.8", "0.4"])
    labels = _frame(id=["c", "a", "b"], label=["0", "1", "1"])

    result = join_labels(current, labels, _schema())

    report = result.report
    assert (report.n_current_rows, report.n_label_rows) == (3, 3)
    assert (report.n_matched, report.n_joined, report.join_rate) == (3, 3, 1.0)
    assert report.n_unmatched_labels == report.n_missing_labels == report.n_invalid_labels == 0
    # Joined rows are in batch order and point at their batch row.
    assert result.joined["record_id"].tolist() == ["a", "b", "c"]
    assert result.joined["row"].tolist() == [0, 1, 2]
    assert result.joined["label"].tolist() == [True, True, False]  # boolean target


def test_a_partial_join_counts_every_kind_of_gap() -> None:
    current = _frame(
        id=["a", "b", "c", "d", "", "f"],
        score=["0.1", "0.2", "0.3", "0.4", "0.5", "0.6"],
    )
    labels = _frame(
        id=["a", "b", "c", "zzz", "NA"],
        label=["1", "", "maybe", "0", "1"],
    )

    report = join_labels(current, labels, _schema()).report

    assert report.n_current_rows == 6
    assert report.n_current_without_id == 1  # ""
    assert report.n_labels_without_id == 1  # "NA"
    assert report.n_matched == 3  # a, b, c
    assert report.n_missing_labels == 1  # b
    assert report.n_invalid_labels == 1  # c: "maybe" is not a boolean
    assert report.n_joined == 1  # a
    assert report.n_unmatched_labels == 1  # zzz
    assert report.join_rate == pytest.approx(1 / 6)


def test_ids_are_exact_text_after_trimming() -> None:
    current = _frame(id=[" a ", "007"], score=["0.1", "0.2"])
    labels = _frame(id=["a", "7"], label=["1", "0"])

    report = join_labels(current, labels, _schema()).report

    assert report.n_joined == 1  # "a" matches " a "; "7" is not "007"
    assert report.n_unmatched_labels == 1


def test_numeric_targets_are_parsed_and_invalid_values_counted() -> None:
    schema = infer_schema(
        _frame(id=["r1", "r2"], y=["1.5", "2.5"]), target_column="y", record_id_column="id"
    )
    current = _frame(id=["a", "b", "c"])
    labels = _frame(id=["a", "b", "c"], y=["3.25", "abc", "inf"])

    result = join_labels(current, labels, schema)

    assert result.report.n_joined == 1
    assert result.report.n_invalid_labels == 2
    assert result.joined["label"].tolist() == [3.25]


def test_extra_label_columns_are_ignored() -> None:
    current = _frame(id=["a"])
    labels = _frame(id=["a"], label=["1"], comment=["late"])

    assert join_labels(current, labels, _schema()).report.n_joined == 1


def test_an_empty_batch_has_no_join_rate() -> None:
    report = join_labels(_frame(id=[]), _frame(id=["a"], label=["1"]), _schema()).report

    assert report.n_current_rows == 0
    assert report.join_rate is None
    assert report.n_unmatched_labels == 1


@pytest.mark.parametrize("part", ["current", "labels"])
def test_duplicate_ids_are_an_error_that_does_not_echo_the_ids(part: str) -> None:
    ids = ["secret-1", "secret-1", "b"]
    current = _frame(id=ids if part == "current" else ["a", "b", "c"])
    labels = _frame(
        id=ids if part == "labels" else ["a"], label=["1"] * (3 if part == "labels" else 1)
    )

    with pytest.raises(JoinError) as raised:
        join_labels(current, labels, _schema())

    assert raised.value.kind == "duplicate-record-ids"
    assert raised.value.errors == [{"part": part, "n_duplicated_ids": 1}]
    assert "secret" not in str(raised.value)


def test_missing_ids_may_repeat() -> None:
    current = _frame(id=["", "", "a"])
    labels = _frame(id=["NA", "NA", "a"], label=["1", "0", "1"])

    report = join_labels(current, labels, _schema()).report

    assert report.n_current_without_id == 2
    assert report.n_labels_without_id == 2
    assert report.n_joined == 1


@pytest.mark.parametrize(
    ("current", "labels", "expected"),
    [
        (_frame(x=["1"]), _frame(id=["a"], label=["1"]), [{"part": "current", "column": "id"}]),
        (_frame(id=["a"]), _frame(id=["a"]), [{"part": "labels", "column": "label"}]),
        (_frame(id=["a"]), _frame(label=["1"]), [{"part": "labels", "column": "id"}]),
    ],
)
def test_absent_columns_are_a_schema_mismatch(
    current: pd.DataFrame, labels: pd.DataFrame, expected: list[dict[str, str]]
) -> None:
    with pytest.raises(JoinError) as raised:
        join_labels(current, labels, _schema())

    assert raised.value.kind == "schema-mismatch"
    assert raised.value.errors == expected


@pytest.mark.parametrize(
    ("roles", "missing"),
    [
        ({"target_column": "label"}, ["record_id_column"]),
        ({"record_id_column": "id"}, ["target_column"]),
        ({}, ["target_column", "record_id_column"]),
    ],
)
def test_a_profile_without_target_or_record_id_cannot_join(
    roles: dict[str, str], missing: list[str]
) -> None:
    schema = infer_schema(_frame(id=["r1"], label=["1"]), **roles)  # type: ignore[arg-type]

    with pytest.raises(JoinError) as raised:
        join_labels(_frame(id=["a"]), _frame(id=["a"], label=["1"]), schema)

    assert raised.value.kind == "labels-not-configured"
    assert raised.value.errors == [{"missing": role} for role in missing]


def test_three_roles_must_be_three_columns() -> None:
    with pytest.raises(ValueError, match="must be different columns"):
        infer_schema(_frame(id=["r1"], label=["1"]), target_column="id", record_id_column="id")
