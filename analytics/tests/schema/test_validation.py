import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.schema import (
    DatasetSchema,
    IssueCode,
    LogicalType,
    Severity,
    ValidationConfig,
    ValidationIssue,
    infer_schema,
    read_csv_text,
    validate_batch,
)
from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    generate_dataset,
    inject_missing,
    shift_mean,
)


def _to_text(df: pd.DataFrame) -> pd.DataFrame:
    """CSV round trip, the same path real uploads take."""
    return read_csv_text(df.to_csv(index=False).encode())


@pytest.fixture
def reference() -> pd.DataFrame:
    return generate_dataset(LOAN_APPLICANT_FEATURES, 2_000, np.random.default_rng(0))


@pytest.fixture
def schema(reference: pd.DataFrame) -> DatasetSchema:
    return infer_schema(_to_text(reference))


@pytest.fixture
def current(reference: pd.DataFrame) -> pd.DataFrame:
    return generate_dataset(LOAN_APPLICANT_FEATURES, 500, np.random.default_rng(1))


def _codes(issues: tuple[ValidationIssue, ...]) -> list[IssueCode]:
    return [issue.code for issue in issues]


def test_clean_batch_is_valid_without_issues(schema: DatasetSchema, current: pd.DataFrame) -> None:
    report = validate_batch(_to_text(current), schema)

    assert report.is_valid
    assert report.issues == ()
    assert report.n_rows == 500
    assert [c.name for c in report.columns] == list(schema.names)
    assert all(c.n_missing == 0 and c.n_invalid == 0 for c in report.columns)


def test_drifted_but_well_formed_batch_is_still_valid(
    schema: DatasetSchema, current: pd.DataFrame
) -> None:
    # Validation is about shape and types, not distributions.
    report = validate_batch(_to_text(shift_mean(current, "age", 30.0)), schema)

    assert report.is_valid
    assert report.issues == ()


def test_missing_monitored_column_makes_batch_invalid(
    schema: DatasetSchema, current: pd.DataFrame
) -> None:
    report = validate_batch(_to_text(current.drop(columns=["income", "region"])), schema)

    assert not report.is_valid
    errors = [i for i in report.issues if i.severity is Severity.ERROR]
    assert {(i.code, i.column) for i in errors} == {
        (IssueCode.MISSING_COLUMN, "income"),
        (IssueCode.MISSING_COLUMN, "region"),
    }
    assert {c.name for c in report.columns} == {"age", "credit_score", "employment"}


def test_extra_column_is_a_warning(schema: DatasetSchema, current: pd.DataFrame) -> None:
    report = validate_batch(_to_text(current.assign(new_feature=1.0)), schema)

    assert report.is_valid
    assert [(i.code, i.column, i.severity) for i in report.issues] == [
        (IssueCode.EXTRA_COLUMN, "new_feature", Severity.WARNING)
    ]
    assert "new_feature" not in report.parsed


def test_column_order_does_not_matter(schema: DatasetSchema, current: pd.DataFrame) -> None:
    reordered = current[list(reversed(current.columns))]

    assert validate_batch(_to_text(reordered), schema).issues == ()


def test_missing_excluded_column_is_only_a_warning(current: pd.DataFrame) -> None:
    reference = current.assign(customer_id=[f"c{i}" for i in range(len(current))])
    schema = infer_schema(_to_text(reference))
    assert not schema.column("customer_id").monitored

    report = validate_batch(_to_text(current), schema)

    assert report.is_valid
    assert _codes(report.issues) == [IssueCode.MISSING_EXCLUDED_COLUMN]


def test_missing_rate_is_reported_exactly(schema: DatasetSchema, current: pd.DataFrame) -> None:
    batch = inject_missing(current, "income", 0.3, np.random.default_rng(2))

    column = validate_batch(_to_text(batch), schema).column("income")

    assert column.n_missing == 150
    assert column.missing_rate == pytest.approx(0.3)
    assert column.n_invalid == 0
    assert not column.schema_violation


def _with_bad_values(current: pd.DataFrame, column: str, n_bad: int) -> pd.DataFrame:
    text = _to_text(current)
    text.loc[: n_bad - 1, column] = "not-a-number"
    return text


def test_invalid_values_within_tolerance_are_counted_not_flagged(
    schema: DatasetSchema, current: pd.DataFrame
) -> None:
    report = validate_batch(_with_bad_values(current, "age", 25), schema)  # 5% of 500

    column = report.column("age")
    assert column.n_invalid == 25
    assert column.invalid_rate == pytest.approx(0.05)
    assert not column.schema_violation
    assert report.issues == ()


def test_invalid_values_above_tolerance_are_a_schema_violation(
    schema: DatasetSchema, current: pd.DataFrame
) -> None:
    report = validate_batch(_with_bad_values(current, "age", 26), schema)

    assert report.is_valid  # value problems never reject the batch
    assert report.column("age").schema_violation
    assert [(i.code, i.column) for i in report.issues] == [(IssueCode.SCHEMA_VIOLATION, "age")]
    # Other columns are unaffected.
    assert not report.column("credit_score").schema_violation


def test_invalid_rate_ignores_missing_values() -> None:
    schema = infer_schema(pd.DataFrame({"x": ["1.5", "2.5", "3.5"]}, dtype="str"))
    batch = pd.DataFrame({"x": ["", "", "", "bad", "1.0"]}, dtype="str")

    column = validate_batch(batch, schema).column("x")

    assert column.n_missing == 3
    assert column.invalid_rate == pytest.approx(0.5)  # 1 invalid of 2 non-missing
    assert column.schema_violation


def test_boolean_columns_are_validated() -> None:
    schema = infer_schema(pd.DataFrame({"flag": ["yes", "no", "yes"]}, dtype="str"))
    batch = pd.DataFrame({"flag": ["yes", "maybe", "no", "no"]}, dtype="str")

    column = validate_batch(batch, schema).column("flag")

    assert column.logical_type is LogicalType.BOOLEAN
    assert column.n_invalid == 1
    assert column.schema_violation


def test_unseen_categories_are_not_validation_errors(
    schema: DatasetSchema, current: pd.DataFrame
) -> None:
    text = _to_text(current)
    text.loc[:99, "region"] = "antarctica"

    report = validate_batch(text, schema)

    # Unseen categories are a drift signal (section 4), not a schema problem.
    assert report.issues == ()
    assert report.column("region").n_invalid == 0


def test_empty_batch_is_valid_with_warning(schema: DatasetSchema) -> None:
    report = validate_batch(read_csv_text(",".join(schema.names).encode() + b"\n"), schema)

    assert report.is_valid
    assert _codes(report.issues) == [IssueCode.EMPTY_BATCH]
    column = report.column("age")
    assert column.missing_rate is None
    assert column.invalid_rate is None


def test_all_missing_column_has_undefined_invalid_rate(
    schema: DatasetSchema, current: pd.DataFrame
) -> None:
    batch = inject_missing(current, "age", 1.0, np.random.default_rng(3))

    column = validate_batch(_to_text(batch), schema).column("age")

    assert column.missing_rate == 1.0
    assert column.invalid_rate is None
    assert not column.schema_violation


def test_parsed_values_are_typed_and_masked(schema: DatasetSchema, current: pd.DataFrame) -> None:
    report = validate_batch(_with_bad_values(current, "age", 3), schema)

    age = report.parsed["age"]
    assert age.values.dtype == "float64"
    assert age.values.isna().sum() == 3
    np.testing.assert_allclose(age.values.iloc[3:].to_numpy(), current["age"].iloc[3:].to_numpy())


def test_custom_invalid_rate_threshold(schema: DatasetSchema, current: pd.DataFrame) -> None:
    report = validate_batch(
        _with_bad_values(current, "age", 1), schema, ValidationConfig(max_invalid_rate=0.0)
    )

    assert report.column("age").schema_violation


@pytest.mark.parametrize("rate", [-0.01, 1.01])
def test_invalid_config_is_rejected(rate: float) -> None:
    with pytest.raises(ValueError, match="max_invalid_rate"):
        ValidationConfig(max_invalid_rate=rate)


def test_validation_is_deterministic(schema: DatasetSchema, current: pd.DataFrame) -> None:
    text = _with_bad_values(current, "age", 40)

    assert validate_batch(text, schema) == validate_batch(text, schema)
