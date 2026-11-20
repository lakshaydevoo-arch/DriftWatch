import math

import pandas as pd
import pytest

from driftwatch_analytics.schema import LogicalType, parse_column


def _text(*values: str) -> pd.Series:
    return pd.Series(list(values), dtype="str")


@pytest.mark.parametrize("token", ["", "  ", "NA", "n/a", "NaN", "null", "NULL", "None"])
def test_missing_tokens_are_missing_not_invalid(token: str) -> None:
    parsed = parse_column(_text("1", token), LogicalType.NUMERIC)

    assert parsed.missing.tolist() == [False, True]
    assert parsed.invalid.tolist() == [False, False]


def test_numeric_parsing_counts_invalid_values() -> None:
    parsed = parse_column(
        _text(" 4 ", "1e3", "-2.5", "abc", "1,000", "inf", ""), LogicalType.NUMERIC
    )

    assert parsed.values.tolist()[:3] == [4.0, 1000.0, -2.5]
    assert all(math.isnan(v) for v in parsed.values.tolist()[3:])
    # "inf" is invalid: infinite values would break every distribution statistic.
    assert parsed.invalid.tolist() == [False, False, False, True, True, True, False]
    assert parsed.n_invalid == 3
    assert parsed.n_missing == 1
    assert parsed.values.dtype == "float64"


def test_boolean_parsing_is_case_insensitive() -> None:
    parsed = parse_column(_text("TRUE", "no", "1", "0", "Yes", "maybe", ""), LogicalType.BOOLEAN)

    assert parsed.values.tolist()[:5] == [True, False, True, False, True]
    assert parsed.invalid.tolist() == [False, False, False, False, False, True, False]
    assert parsed.n_missing == 1


def test_datetime_parsing_accepts_iso8601_only() -> None:
    parsed = parse_column(
        _text("2024-01-05", "2024-01-05T10:30:00Z", "01/02/2024"), LogicalType.DATETIME
    )

    assert parsed.invalid.tolist() == [False, False, True]
    assert isinstance(parsed.values.dtype, pd.DatetimeTZDtype)
    assert str(parsed.values.dtype.tz) == "UTC"


def test_categorical_values_are_stripped_and_case_preserved() -> None:
    parsed = parse_column(_text(" Yes", "yes ", "YES", ""), LogicalType.CATEGORICAL)

    assert parsed.values.tolist()[:3] == ["Yes", "yes", "YES"]
    assert parsed.n_invalid == 0
    assert parsed.n_missing == 1


def test_integer_coded_categoricals_normalise_float_spelling() -> None:
    # pandas writes integer columns that contain NaN as floats ("3.0"); the
    # category must not change because of how the file was written.
    parsed = parse_column(
        _text("3", "3.0", " 5 ", "2.5", "x"), LogicalType.CATEGORICAL, integer_coded=True
    )

    assert parsed.values.tolist() == ["3", "3", "5", "2.5", "x"]


def test_integer_normalisation_does_not_overflow_on_huge_numbers() -> None:
    parsed = parse_column(_text("1e300", "7"), LogicalType.CATEGORICAL, integer_coded=True)

    assert parsed.values.tolist() == ["1e300", "7"]


def test_values_are_missing_where_missing_or_invalid() -> None:
    parsed = parse_column(_text("1", "", "bad"), LogicalType.NUMERIC)

    assert parsed.values.isna().tolist() == [False, True, True]
