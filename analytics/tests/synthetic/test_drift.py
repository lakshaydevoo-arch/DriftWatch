import math

import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    generate_dataset,
    inject_missing,
    resample_categories,
    scale_std,
    shift_mean,
)

N_ROWS = 20_000


@pytest.fixture
def reference() -> pd.DataFrame:
    return generate_dataset(LOAN_APPLICANT_FEATURES, N_ROWS, np.random.default_rng(42))


# --- shift_mean -------------------------------------------------------------


def test_shift_mean_adds_delta_to_every_value(reference: pd.DataFrame) -> None:
    drifted = shift_mean(reference, "age", 5.0)

    np.testing.assert_allclose(drifted["age"] - reference["age"], 5.0)
    assert drifted["age"].std() == pytest.approx(reference["age"].std())


def test_shift_mean_leaves_other_columns_and_input_untouched(reference: pd.DataFrame) -> None:
    original = reference.copy()

    drifted = shift_mean(reference, "age", 5.0)

    pd.testing.assert_frame_equal(reference, original)
    pd.testing.assert_frame_equal(drifted.drop(columns="age"), reference.drop(columns="age"))


def test_shift_mean_keeps_missing_values_missing(reference: pd.DataFrame) -> None:
    with_missing = inject_missing(reference, "age", 0.1, np.random.default_rng(0))

    drifted = shift_mean(with_missing, "age", 5.0)

    pd.testing.assert_series_equal(drifted["age"].isna(), with_missing["age"].isna())


# --- scale_std --------------------------------------------------------------


@pytest.mark.parametrize("factor", [0.5, 1.0, 2.0])
def test_scale_std_scales_spread_and_preserves_mean(reference: pd.DataFrame, factor: float) -> None:
    drifted = scale_std(reference, "credit_score", factor)

    assert drifted["credit_score"].mean() == pytest.approx(reference["credit_score"].mean())
    assert drifted["credit_score"].std() == pytest.approx(factor * reference["credit_score"].std())


def test_scale_std_ignores_missing_values_when_computing_mean(reference: pd.DataFrame) -> None:
    with_missing = inject_missing(reference, "age", 0.2, np.random.default_rng(1))

    drifted = scale_std(with_missing, "age", 2.0)

    assert drifted["age"].isna().sum() == with_missing["age"].isna().sum()
    assert drifted["age"].mean() == pytest.approx(with_missing["age"].mean())


# --- resample_categories ----------------------------------------------------


def test_resample_categories_matches_new_proportions(reference: pd.DataFrame) -> None:
    categories = ("north", "south", "east", "west")
    probabilities = (0.10, 0.10, 0.10, 0.70)

    drifted = resample_categories(
        reference, "region", categories, probabilities, np.random.default_rng(5)
    )

    observed = drifted["region"].value_counts(normalize=True)
    for category, p in zip(categories, probabilities, strict=True):
        assert abs(observed[category] - p) < 5 * math.sqrt(p * (1 - p) / N_ROWS)


def test_resample_categories_can_introduce_unseen_category(reference: pd.DataFrame) -> None:
    drifted = resample_categories(
        reference,
        "employment",
        ("salaried", "self_employed", "unemployed", "student"),
        (0.6, 0.2, 0.1, 0.1),
        np.random.default_rng(6),
    )

    assert "student" not in set(reference["employment"])
    assert "student" in set(drifted["employment"])


def test_resample_categories_preserves_missing_positions(reference: pd.DataFrame) -> None:
    with_missing = inject_missing(reference, "region", 0.15, np.random.default_rng(2))

    drifted = resample_categories(
        with_missing, "region", ("north", "south"), (0.5, 0.5), np.random.default_rng(3)
    )

    pd.testing.assert_series_equal(drifted["region"].isna(), with_missing["region"].isna())
    assert pd.api.types.is_string_dtype(drifted["region"])


def test_resample_categories_does_not_mutate_input(reference: pd.DataFrame) -> None:
    original = reference.copy()

    resample_categories(reference, "region", ("north",), (1.0,), np.random.default_rng(4))

    pd.testing.assert_frame_equal(reference, original)


def test_resample_categories_validates_distribution(reference: pd.DataFrame) -> None:
    with pytest.raises(ValueError, match="sum to 1"):
        resample_categories(
            reference, "region", ("north", "south"), (0.5, 0.6), np.random.default_rng(0)
        )


# --- inject_missing ---------------------------------------------------------


@pytest.mark.parametrize("rate", [0.0, 0.05, 0.5, 1.0])
def test_inject_missing_sets_exact_count(reference: pd.DataFrame, rate: float) -> None:
    drifted = inject_missing(reference, "income", rate, np.random.default_rng(8))

    assert drifted["income"].isna().sum() == round(rate * N_ROWS)
    pd.testing.assert_frame_equal(drifted.drop(columns="income"), reference.drop(columns="income"))


def test_inject_missing_works_on_string_columns(reference: pd.DataFrame) -> None:
    drifted = inject_missing(reference, "region", 0.3, np.random.default_rng(9))

    assert drifted["region"].isna().sum() == round(0.3 * N_ROWS)
    assert pd.api.types.is_string_dtype(drifted["region"])


def test_inject_missing_is_reproducible(reference: pd.DataFrame) -> None:
    a = inject_missing(reference, "age", 0.1, np.random.default_rng(10))
    b = inject_missing(reference, "age", 0.1, np.random.default_rng(10))

    pd.testing.assert_frame_equal(a, b)


# --- validation shared by all injectors -------------------------------------


def test_unknown_column_is_rejected(reference: pd.DataFrame) -> None:
    with pytest.raises(KeyError, match="not_a_column"):
        shift_mean(reference, "not_a_column", 1.0)
    with pytest.raises(KeyError, match="not_a_column"):
        inject_missing(reference, "not_a_column", 0.1, np.random.default_rng(0))


def test_numeric_injectors_reject_non_numeric_columns(reference: pd.DataFrame) -> None:
    with pytest.raises(TypeError, match="numeric"):
        shift_mean(reference, "region", 1.0)
    with pytest.raises(TypeError, match="numeric"):
        scale_std(reference, "region", 2.0)


def test_numeric_injectors_reject_boolean_columns() -> None:
    df = pd.DataFrame({"flag": [True, False, True]})

    with pytest.raises(TypeError, match="numeric"):
        shift_mean(df, "flag", 1.0)


@pytest.mark.parametrize("factor", [0.0, -1.0, math.nan, math.inf])
def test_scale_std_rejects_invalid_factor(reference: pd.DataFrame, factor: float) -> None:
    with pytest.raises(ValueError, match="factor"):
        scale_std(reference, "age", factor)


def test_scale_std_rejects_all_missing_column() -> None:
    df = pd.DataFrame({"x": [np.nan, np.nan]}, dtype="float64")

    with pytest.raises(ValueError, match="no non-missing"):
        scale_std(df, "x", 2.0)


@pytest.mark.parametrize("rate", [-0.1, 1.1, math.nan])
def test_inject_missing_rejects_invalid_rate(reference: pd.DataFrame, rate: float) -> None:
    with pytest.raises(ValueError, match="rate"):
        inject_missing(reference, "age", rate, np.random.default_rng(0))


def test_shift_mean_rejects_non_finite_delta(reference: pd.DataFrame) -> None:
    with pytest.raises(ValueError, match="delta"):
        shift_mean(reference, "age", math.inf)
