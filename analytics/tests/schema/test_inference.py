import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.schema import (
    DatasetSchema,
    ExclusionReason,
    InferenceConfig,
    LogicalType,
    TypeSource,
    infer_schema,
    read_csv_text,
)
from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    generate_dataset,
    inject_missing,
)


def _frame(**columns: list[str]) -> pd.DataFrame:
    return pd.DataFrame(columns, dtype="str")


def _types(schema: DatasetSchema) -> dict[str, LogicalType]:
    return {c.name: c.logical_type for c in schema.columns}


def test_infers_synthetic_dataset_after_csv_round_trip() -> None:
    df = generate_dataset(LOAN_APPLICANT_FEATURES, 1_000, np.random.default_rng(0))
    df = inject_missing(df, "income", 0.2, np.random.default_rng(1))
    text = read_csv_text(df.to_csv(index=False).encode())

    schema = infer_schema(text)

    assert _types(schema) == {
        "age": LogicalType.NUMERIC,
        "income": LogicalType.NUMERIC,
        "credit_score": LogicalType.NUMERIC,
        "region": LogicalType.CATEGORICAL,
        "employment": LogicalType.CATEGORICAL,
    }
    assert all(c.monitored and c.source is TypeSource.INFERRED for c in schema.columns)
    assert schema.names == tuple(df.columns)


def test_boolean_tokens_are_inferred_as_boolean() -> None:
    schema = infer_schema(_frame(a=["yes", "No", "YES", ""], b=["1", "0", "1", "0"]))

    assert _types(schema) == {"a": LogicalType.BOOLEAN, "b": LogicalType.BOOLEAN}


def test_few_distinct_integers_are_categorical_and_integer_coded() -> None:
    schema = infer_schema(_frame(rating=["1", "2", "3", "4.0", "5", "2"]))

    column = schema.column("rating")
    assert column.logical_type is LogicalType.CATEGORICAL
    assert column.integer_coded


def test_many_distinct_integers_are_numeric() -> None:
    schema = infer_schema(_frame(age=[str(v) for v in range(18, 40)]))

    assert schema.column("age").logical_type is LogicalType.NUMERIC
    assert not schema.column("age").integer_coded


def test_numeric_tolerates_up_to_five_percent_invalid_values() -> None:
    values = [str(v / 10) for v in range(95)] + ["bad"] * 5
    schema = infer_schema(_frame(x=values))

    assert schema.column("x").logical_type is LogicalType.NUMERIC


def test_more_than_five_percent_invalid_values_is_not_numeric() -> None:
    values = [str(v / 10) for v in range(94)] + ["bad"] * 6
    schema = infer_schema(_frame(x=values))

    assert schema.column("x").logical_type is LogicalType.CATEGORICAL


def test_iso_dates_are_datetime_and_excluded() -> None:
    schema = infer_schema(_frame(when=["2024-01-01", "2024-02-03T04:05:06Z", ""]))

    column = schema.column("when")
    assert column.logical_type is LogicalType.DATETIME
    assert column.excluded is ExclusionReason.DATETIME_NOT_SUPPORTED
    assert not column.monitored


def test_all_missing_column_is_unknown_and_excluded() -> None:
    schema = infer_schema(_frame(x=["", "NA", "null"]))

    column = schema.column("x")
    assert column.logical_type is LogicalType.UNKNOWN
    assert column.excluded is ExclusionReason.ALL_MISSING


def test_unique_strings_are_flagged_as_identifiers() -> None:
    schema = infer_schema(_frame(customer_id=[f"cust-{i}" for i in range(100)]))

    assert schema.column("customer_id").excluded is ExclusionReason.LIKELY_IDENTIFIER


def test_identifier_rule_needs_enough_rows() -> None:
    # 10 distinct cities in 10 rows is not evidence of an identifier column.
    schema = infer_schema(_frame(city=[f"city-{i}" for i in range(10)]))

    assert schema.column("city").monitored


def test_overrides_and_exclusions() -> None:
    data = _frame(zip_code=["10001", "94105", "60601"], score=["1.5", "2.5", "3.5"])

    schema = infer_schema(
        data,
        overrides={"zip_code": LogicalType.CATEGORICAL},
        exclude=["score"],
    )

    zip_code = schema.column("zip_code")
    assert zip_code.logical_type is LogicalType.CATEGORICAL
    assert zip_code.source is TypeSource.OVERRIDE
    assert zip_code.integer_coded
    assert zip_code.monitored
    score = schema.column("score")
    assert score.logical_type is LogicalType.NUMERIC
    assert score.excluded is ExclusionReason.USER_EXCLUDED


def test_inference_is_deterministic() -> None:
    df = generate_dataset(LOAN_APPLICANT_FEATURES, 500, np.random.default_rng(3))
    text = read_csv_text(df.to_csv(index=False).encode())

    assert infer_schema(text) == infer_schema(text)


def test_rejects_dataset_without_rows() -> None:
    with pytest.raises(ValueError, match="no rows"):
        infer_schema(read_csv_text(b"a,b\n"))


def test_rejects_overrides_for_unknown_columns() -> None:
    with pytest.raises(ValueError, match="unknown columns: \\['nope'\\]"):
        infer_schema(_frame(a=["1"]), overrides={"nope": LogicalType.NUMERIC})
    with pytest.raises(ValueError, match="unknown columns: \\['nope'\\]"):
        infer_schema(_frame(a=["1"]), exclude=["nope"])


def test_rejects_unknown_as_override() -> None:
    with pytest.raises(ValueError, match="UNKNOWN"):
        infer_schema(_frame(a=["1"]), overrides={"a": LogicalType.UNKNOWN})


@pytest.mark.parametrize(
    "kwargs",
    [
        {"numeric_ratio": 0.0},
        {"numeric_ratio": 1.5},
        {"max_integer_categories": -1},
        {"identifier_min_count": 0},
    ],
)
def test_invalid_config_is_rejected(kwargs: dict[str, float]) -> None:
    with pytest.raises(ValueError):
        InferenceConfig(**kwargs)  # type: ignore[arg-type]


def test_schema_rejects_duplicate_column_names() -> None:
    schema = infer_schema(_frame(a=["1"]))

    with pytest.raises(ValueError, match="unique"):
        DatasetSchema(schema.columns * 2)
