import math
from typing import Any

import numpy as np
import pandas as pd
import pytest
from pydantic import ValidationError
from scipy.stats import chi2_contingency

from driftwatch_analytics.drift import (
    CategoricalDriftConfig,
    CategoricalDriftResult,
    categorical_drift,
    pool_rare_buckets,
)
from driftwatch_analytics.profiling import (
    CategoricalProfile,
    ProfileConfig,
    build_reference_profile,
    category_labels,
)
from driftwatch_analytics.schema import infer_schema, read_csv_text, validate_batch
from driftwatch_analytics.synthetic import CategoricalFeature, generate_dataset, resample_categories

N = 5_000
CATEGORIES = ("a", "b", "c", "d")
PROBABILITIES = (0.30, 0.25, 0.25, 0.20)
FEATURE = (CategoricalFeature("c", CATEGORIES, PROBABILITIES),)


def _profile(values: list[str], config: ProfileConfig | None = None) -> CategoricalProfile:
    text = read_csv_text(pd.DataFrame({"c": values}).to_csv(index=False).encode())
    feature = build_reference_profile(text, infer_schema(text), config).features["c"]
    assert isinstance(feature, CategoricalProfile)
    return feature


def _sample(n: int, seed: int) -> list[str]:
    return generate_dataset(FEATURE, n, np.random.default_rng(seed))["c"].tolist()


@pytest.fixture(scope="module")
def reference() -> CategoricalProfile:
    return _profile(_sample(N, 0))


def _js_bits(p: list[float], q: list[float]) -> float:
    """Independent Jensen-Shannon distance (base 2) from the definition."""
    m = [(a + b) / 2 for a, b in zip(p, q, strict=True)]

    def kl(x: list[float]) -> float:
        return sum(a * math.log2(a / b) for a, b in zip(x, m, strict=True) if a > 0)

    return math.sqrt((kl(p) + kl(q)) / 2)


# --- exact values ------------------------------------------------------------


def test_js_distance_matches_definition() -> None:
    reference = _profile(["a"] * 50 + ["b"] * 50)

    result = categorical_drift(reference, ["a"] * 80 + ["b"] * 20)

    assert result.js_distance == pytest.approx(_js_bits([0.5, 0.5], [0.8, 0.2]))
    assert result.categories == ("a", "b")
    assert result.current_proportions == (0.8, 0.2)


def test_chi_square_matches_scipy_on_the_count_table() -> None:
    reference = _profile(["a"] * 50 + ["b"] * 50)

    result = categorical_drift(reference, ["a"] * 80 + ["b"] * 20)

    expected = chi2_contingency(np.array([[50, 50], [80, 20]]), correction=False)
    assert result.chi2_statistic == pytest.approx(expected.statistic)
    assert result.chi2_p_value == pytest.approx(expected.pvalue)
    assert result.chi2_dof == 1


def test_identical_distribution_gives_zero_distance() -> None:
    reference = _profile(["a"] * 40 + ["b"] * 60)

    result = categorical_drift(reference, ["a"] * 20 + ["b"] * 30)

    assert result.js_distance == 0.0
    assert not result.drift_detected
    assert result.chi2_p_value == pytest.approx(1.0)


def test_completely_new_categories_give_maximum_distance() -> None:
    reference = _profile(["a"] * 100)

    result = categorical_drift(reference, ["x"] * 30 + ["y"] * 20)

    assert result.js_distance == pytest.approx(1.0)
    assert result.drift_detected
    assert result.unseen_categories == ("x", "y")


# --- behaviour on known drift --------------------------------------------------


def test_same_distribution_is_not_flagged(reference: CategoricalProfile) -> None:
    result = categorical_drift(reference, _sample(N, 1))

    assert not result.drift_detected
    assert result.js_distance < 0.03
    assert result.chi2_p_value is not None and result.chi2_p_value > 0.01


def test_proportion_shift_is_detected(reference: CategoricalProfile) -> None:
    df = generate_dataset(FEATURE, N, np.random.default_rng(2))
    shifted = resample_categories(
        df, "c", CATEGORIES, (0.10, 0.10, 0.40, 0.40), np.random.default_rng(3)
    )

    result = categorical_drift(reference, shifted["c"].tolist())

    assert result.drift_detected
    assert result.chi2_p_value is not None and result.chi2_p_value < 1e-10
    assert result.unseen_categories == ()


def test_js_increases_with_shift_size(reference: CategoricalProfile) -> None:
    df = generate_dataset(FEATURE, N, np.random.default_rng(4))
    distances = []
    for weight in (0.0, 0.2, 0.5, 0.8):
        # Blend the reference proportions towards all mass on "d".
        target = tuple(
            (1 - weight) * p + (weight if c == "d" else 0.0)
            for c, p in zip(CATEGORIES, PROBABILITIES, strict=True)
        )
        drifted = resample_categories(df, "c", CATEGORIES, target, np.random.default_rng(5))
        distances.append(categorical_drift(reference, drifted["c"].tolist()).js_distance)

    assert distances == sorted(distances)


def test_small_shift_large_sample_chi2_significant_but_js_not(
    reference: CategoricalProfile,
) -> None:
    # Same large-sample caveat as KS (ML_MONITORING_DESIGN.md section 4.2).
    df = generate_dataset(FEATURE, 20_000, np.random.default_rng(6))
    slight = resample_categories(
        df, "c", CATEGORIES, (0.28, 0.25, 0.25, 0.22), np.random.default_rng(7)
    )

    result = categorical_drift(reference, slight["c"].tolist())

    assert result.chi2_p_value is not None and result.chi2_p_value < 0.01
    assert not result.drift_detected


# --- unseen categories and truncated profiles ------------------------------------


def test_unseen_category_gets_its_own_bucket(reference: CategoricalProfile) -> None:
    current = _sample(900, 8) + ["e"] * 100

    result = categorical_drift(reference, current)

    assert result.categories == ("a", "b", "c", "d", "e")
    assert result.reference_proportions[-1] == 0.0
    assert result.current_proportions[-1] == pytest.approx(0.1)
    assert result.unseen_categories == ("e",)
    assert not result.reference_truncated


def test_truncated_profile_merges_unseen_into_other_bucket() -> None:
    # 10 reference categories, profile keeps the top 3 (cat0-cat2).
    values = [f"cat{i}" for i in range(10) for _ in range(100 - 5 * i)]
    reference = _profile(values, ProfileConfig(max_categories=3))
    assert reference.other_count > 0

    # Same distribution again: the tail categories are "unseen" as far as the
    # profile knows, but must not be counted as drift.
    result = categorical_drift(reference, values)

    assert result.reference_truncated
    assert result.categories == ("cat0", "cat1", "cat2", None)
    assert result.js_distance == pytest.approx(0.0, abs=1e-12)
    assert set(result.unseen_categories) == {f"cat{i}" for i in range(3, 10)}


def test_unseen_categories_report_is_capped_but_counted(reference: CategoricalProfile) -> None:
    current = _sample(500, 9) + [f"new{i:02d}" for i in range(30)]

    result = categorical_drift(reference, current, CategoricalDriftConfig(max_reported_unseen=5))

    assert result.unseen_categories == ("new00", "new01", "new02", "new03", "new04")
    assert result.n_unseen_categories == 30


# --- rare-category pooling for chi-square ---------------------------------------


def test_pooling_merges_columns_with_small_expected_counts() -> None:
    # N = 414, row totals 207 -> a column needs total >= 5 * 414 / 207 = 10.
    # Columns 2 and 3 (7 each) are too small alone but enough together.
    table = np.array([[100, 100, 4, 3], [100, 100, 3, 4]])

    groups = pool_rare_buckets(table, 5.0)

    assert groups == [[0], [1], [2, 3]]


def test_pool_that_is_still_too_small_absorbs_the_smallest_column() -> None:
    # Required column total = 5 * 267 / 133 ~ 10.04. Columns 2 and 3 pool to
    # 7, still too small, so the smallest adequate column (1) is absorbed.
    table = np.array([[100, 30, 3, 1], [100, 30, 2, 1]])

    groups = pool_rare_buckets(table, 5.0)

    assert groups == [[0], [1, 2, 3]]


def test_pooling_with_too_little_data_leaves_fewer_than_two_groups() -> None:
    assert len(pool_rare_buckets(np.array([[3, 2], [1, 1]]), 5.0)) < 2


def test_rare_categories_are_pooled_in_the_test(reference: CategoricalProfile) -> None:
    # Required column total = 5 * 6035 / 1035 ~ 29.2: 20 and 15 are each too
    # small, together (35) they are enough, so only they are pooled.
    current = _sample(1_000, 10) + ["rare1"] * 20 + ["rare2"] * 15

    result = categorical_drift(reference, current)

    assert set(result.chi2_pooled) == {"rare1", "rare2"}
    assert result.chi2_dof == 4  # a, b, c, d + pooled -> 5 columns
    assert result.chi2_p_value is not None


def test_chi_square_skipped_with_warning_on_tiny_batches(reference: CategoricalProfile) -> None:
    result = categorical_drift(reference, ["a", "b"])

    assert result.chi2_p_value is None
    assert result.chi2_statistic is None
    assert result.warnings and "skipped" in result.warnings[0]
    assert 0.0 <= result.js_distance <= 1.0


# --- null behaviour over repeated trials -----------------------------------------


def test_false_positive_rates_under_no_drift() -> None:
    n, trials = 1_000, 200
    flags, rejections = 0, 0
    for trial in range(trials):
        reference = _profile(_sample(n, 30_000 + trial))
        result = categorical_drift(reference, _sample(n, 40_000 + trial))
        flags += result.drift_detected
        assert result.chi2_p_value is not None
        rejections += result.chi2_p_value < 0.05

    # Mean no-drift JS is ~0.03 at n=1000 for 4 categories, far below 0.1.
    assert flags == 0
    # About 5% of chi-square p-values fall below 0.05 under the null.
    assert 1 <= rejections <= 20


# --- integration with the rest of the pipeline ---------------------------------


def test_boolean_feature_end_to_end() -> None:
    reference_text = pd.DataFrame({"flag": ["yes"] * 60 + ["no"] * 40}, dtype="str")
    schema = infer_schema(reference_text)
    profile = build_reference_profile(reference_text, schema).features["flag"]
    assert isinstance(profile, CategoricalProfile)

    current_text = pd.DataFrame({"flag": ["TRUE"] * 20 + ["false"] * 80}, dtype="str")
    parsed = validate_batch(current_text, schema).parsed["flag"]
    labels = category_labels(parsed.values, schema.column("flag").logical_type)

    result = categorical_drift(profile, labels)

    assert result.categories == ("false", "true")
    assert result.current_proportions == (0.8, 0.2)
    assert result.drift_detected


def test_result_round_trips_through_json(reference: CategoricalProfile) -> None:
    truncated = _profile(
        [f"c{i}" for i in range(5) for _ in range(10)], ProfileConfig(max_categories=2)
    )
    for profile, current in ((reference, _sample(300, 11)), (truncated, ["c0", "c9", "c9"])):
        result = categorical_drift(profile, current)
        assert CategoricalDriftResult.model_validate_json(result.model_dump_json()) == result


def test_results_are_deterministic(reference: CategoricalProfile) -> None:
    current = [*_sample(N, 12), "z", "y"]

    assert categorical_drift(reference, current) == categorical_drift(reference, current)


# --- invalid input and config ------------------------------------------------------


def test_empty_current_is_rejected(reference: CategoricalProfile) -> None:
    with pytest.raises(ValueError, match="empty"):
        categorical_drift(reference, [])


def test_non_string_current_is_rejected(reference: CategoricalProfile) -> None:
    with pytest.raises(ValueError, match="strings"):
        categorical_drift(reference, ["a", None])  # type: ignore[list-item]


@pytest.mark.parametrize(
    "kwargs", [{"js_threshold": 0.0}, {"js_threshold": 1.5}, {"chi2_min_expected": 0.0}]
)
def test_invalid_config_is_rejected(kwargs: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        CategoricalDriftConfig(**kwargs)
