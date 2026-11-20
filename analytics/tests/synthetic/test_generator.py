import math

import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    CategoricalFeature,
    LogNormalFeature,
    NormalFeature,
    generate_dataset,
)

# Large enough that sampling error is small relative to the tolerances below.
N_ROWS = 50_000
# Tolerances are expressed in standard errors. Results are deterministic for a
# fixed seed, but tying the bound to sampling theory (rather than to whatever
# one seed happened to produce) keeps the tests meaningful if the seed changes.
MAX_STANDARD_ERRORS = 5.0


def test_same_seed_gives_identical_data() -> None:
    a = generate_dataset(LOAN_APPLICANT_FEATURES, 1_000, np.random.default_rng(7))
    b = generate_dataset(LOAN_APPLICANT_FEATURES, 1_000, np.random.default_rng(7))

    pd.testing.assert_frame_equal(a, b)


def test_different_seeds_give_different_data() -> None:
    a = generate_dataset(LOAN_APPLICANT_FEATURES, 1_000, np.random.default_rng(1))
    b = generate_dataset(LOAN_APPLICANT_FEATURES, 1_000, np.random.default_rng(2))

    assert not a.equals(b)


def test_columns_dtypes_and_shape() -> None:
    df = generate_dataset(LOAN_APPLICANT_FEATURES, 100, np.random.default_rng(0))

    assert list(df.columns) == ["age", "income", "credit_score", "region", "employment"]
    assert df.shape == (100, 5)
    for column in ("age", "income", "credit_score"):
        assert df[column].dtype == "float64"
    for column in ("region", "employment"):
        assert pd.api.types.is_string_dtype(df[column])
    assert df.notna().all().all()


def test_appending_a_spec_keeps_earlier_columns_unchanged() -> None:
    base = (NormalFeature("x", 0.0, 1.0),)
    extended = (*base, NormalFeature("y", 5.0, 2.0))

    a = generate_dataset(base, 500, np.random.default_rng(3))
    b = generate_dataset(extended, 500, np.random.default_rng(3))

    pd.testing.assert_series_equal(a["x"], b["x"])


def test_normal_feature_matches_requested_moments() -> None:
    spec = NormalFeature("x", mean=10.0, std=3.0)
    values = generate_dataset((spec,), N_ROWS, np.random.default_rng(11))["x"]

    standard_error = spec.std / math.sqrt(N_ROWS)
    assert abs(values.mean() - spec.mean) < MAX_STANDARD_ERRORS * standard_error
    # Std of the sample std for a normal distribution is about std / sqrt(2n).
    assert abs(values.std() - spec.std) < MAX_STANDARD_ERRORS * spec.std / math.sqrt(2 * N_ROWS)


def test_lognormal_feature_is_positive_and_log_normal() -> None:
    spec = LogNormalFeature("income", mean_log=10.0, sigma=0.5)
    values = generate_dataset((spec,), N_ROWS, np.random.default_rng(12))["income"]

    assert (values > 0).all()
    log_values = np.log(values)
    assert abs(log_values.mean() - spec.mean_log) < MAX_STANDARD_ERRORS * spec.sigma / math.sqrt(
        N_ROWS
    )
    # Right skew: mean above median.
    assert values.mean() > values.median()


def test_categorical_feature_matches_requested_proportions() -> None:
    spec = CategoricalFeature("c", categories=("a", "b", "c"), probabilities=(0.6, 0.3, 0.1))
    values = generate_dataset((spec,), N_ROWS, np.random.default_rng(13))["c"]

    observed = values.value_counts(normalize=True)
    assert set(observed.index) == {"a", "b", "c"}
    for category, p in zip(spec.categories, spec.probabilities, strict=True):
        standard_error = math.sqrt(p * (1 - p) / N_ROWS)
        assert abs(observed[category] - p) < MAX_STANDARD_ERRORS * standard_error


def test_zero_probability_category_never_appears() -> None:
    spec = CategoricalFeature("c", categories=("a", "b"), probabilities=(1.0, 0.0))
    values = generate_dataset((spec,), 1_000, np.random.default_rng(14))["c"]

    assert set(values) == {"a"}


@pytest.mark.parametrize("n_rows", [0, -5])
def test_rejects_non_positive_row_count(n_rows: int) -> None:
    with pytest.raises(ValueError, match="n_rows"):
        generate_dataset(LOAN_APPLICANT_FEATURES, n_rows, np.random.default_rng(0))


def test_rejects_empty_specs() -> None:
    with pytest.raises(ValueError, match="at least one"):
        generate_dataset((), 10, np.random.default_rng(0))


def test_rejects_duplicate_feature_names() -> None:
    specs = (NormalFeature("x", 0.0, 1.0), NormalFeature("x", 1.0, 1.0))

    with pytest.raises(ValueError, match="duplicate"):
        generate_dataset(specs, 10, np.random.default_rng(0))
