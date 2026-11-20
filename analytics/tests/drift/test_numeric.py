import math

import numpy as np
import pandas as pd
import pytest
from pydantic import ValidationError
from scipy.stats import wasserstein_distance

from driftwatch_analytics.drift import (
    NumericDriftConfig,
    NumericDriftResult,
    numeric_drift,
    population_stability_index,
)
from driftwatch_analytics.profiling import NumericProfile, ProfileConfig, build_reference_profile
from driftwatch_analytics.schema import LogicalType, infer_schema, read_csv_text
from driftwatch_analytics.synthetic import (
    LogNormalFeature,
    NormalFeature,
    generate_dataset,
    scale_std,
    shift_mean,
)

N = 5_000
FEATURE = (NormalFeature("x", mean=0.0, std=1.0),)


def _profile(values: pd.Series, config: ProfileConfig | None = None) -> NumericProfile:
    """Build a profile through the real CSV -> schema -> profile path."""
    text = read_csv_text(values.to_frame("x").to_csv(index=False).encode())
    feature = build_reference_profile(text, infer_schema(text), config).features["x"]
    assert isinstance(feature, NumericProfile)
    return feature


def _normal(n: int, seed: int) -> pd.DataFrame:
    return generate_dataset(FEATURE, n, np.random.default_rng(seed))


@pytest.fixture(scope="module")
def reference() -> NumericProfile:
    return _profile(_normal(N, 0)["x"])


# --- PSI formula -------------------------------------------------------------


def test_psi_matches_hand_computation() -> None:
    r = np.array([0.5, 0.5])
    c = np.array([0.8, 0.2])
    expected = 0.3 * math.log(0.8 / 0.5) + (-0.3) * math.log(0.2 / 0.5)  # 0.4159

    assert population_stability_index(r, c, 1e-4) == pytest.approx(expected)
    assert expected == pytest.approx(0.4159, abs=1e-4)


def test_psi_is_zero_for_identical_and_symmetric_otherwise() -> None:
    r = np.array([0.1, 0.2, 0.3, 0.4])
    c = np.array([0.25, 0.25, 0.25, 0.25])

    assert population_stability_index(r, r, 1e-4) == 0.0
    assert population_stability_index(r, c, 1e-4) == pytest.approx(
        population_stability_index(c, r, 1e-4)
    )


def test_psi_with_empty_bin_uses_epsilon_and_stays_finite() -> None:
    r = np.array([0.5, 0.5, 0.0])
    c = np.array([0.4, 0.4, 0.2])
    eps = 1e-4
    expected = 2 * (0.4 - 0.5) * math.log(0.4 / 0.5) + (0.2 - eps) * math.log(0.2 / eps)

    assert population_stability_index(r, c, eps) == pytest.approx(expected)


def test_psi_rejects_mismatched_bins() -> None:
    with pytest.raises(ValueError, match="same number of bins"):
        population_stability_index(np.array([1.0]), np.array([0.5, 0.5]), 1e-4)


# --- behaviour on known drift --------------------------------------------------


def test_same_distribution_psi_close_to_sampling_noise(reference: NumericProfile) -> None:
    result = numeric_drift(reference, _normal(N, 1)["x"].to_numpy())

    # Under no drift E[PSI] ~ (bins - 1) * (1/n_ref + 1/n_cur) = 0.0036 here.
    expected_noise = (result.n_bins - 1) * (1 / N + 1 / N)
    assert result.psi < 5 * expected_noise
    assert not result.drift_detected
    assert result.ks_p_value > 0.01


@pytest.mark.parametrize(("shift", "detected"), [(0.5, True), (1.0, True), (0.25, False)])
def test_mean_shift_detection(reference: NumericProfile, shift: float, detected: bool) -> None:
    current = shift_mean(_normal(N, 2), "x", shift)["x"].to_numpy()

    result = numeric_drift(reference, current)

    # For normal data a shift of d standard deviations gives PSI ~ d^2.
    assert result.psi == pytest.approx(shift**2, rel=0.35)
    assert result.drift_detected is detected


def test_psi_increases_with_shift_size(reference: NumericProfile) -> None:
    base = _normal(N, 3)
    psis = [
        numeric_drift(reference, shift_mean(base, "x", d)["x"].to_numpy()).psi
        for d in (0.0, 0.25, 0.5, 1.0, 2.0)
    ]

    assert psis == sorted(psis)
    assert psis[-1] > 2.0


def test_variance_change_is_detected_with_mean_unchanged(reference: NumericProfile) -> None:
    current = scale_std(_normal(N, 4), "x", 2.0)["x"].to_numpy()

    result = numeric_drift(reference, current)

    assert result.drift_detected
    # Mass moves from the middle bins to the outer bins.
    assert result.current_proportions[0] > 2 * result.reference_proportions[0]
    assert result.current_proportions[-1] > 2 * result.reference_proportions[-1]


def test_small_shift_large_sample_ks_significant_but_psi_not(reference: NumericProfile) -> None:
    # The large-sample caveat (ML_MONITORING_DESIGN.md section 3.2): a 0.1 sd
    # shift is statistically detectable with 5,000 rows but practically small.
    current = shift_mean(_normal(N, 5), "x", 0.1)["x"].to_numpy()

    result = numeric_drift(reference, current)

    assert result.ks_p_value < 0.001
    assert not result.drift_detected


def test_values_outside_reference_range_are_counted(reference: NumericProfile) -> None:
    current = np.full(100, reference.max + 100.0)

    result = numeric_drift(reference, current)

    assert result.current_proportions[-1] == 1.0
    assert result.drift_detected
    assert result.ks_statistic == pytest.approx(1.0)


@pytest.mark.parametrize(("factor", "detected"), [(1.3, False), (2.0, True)])
def test_multiplicative_change_on_skewed_feature(factor: float, detected: bool) -> None:
    # PSI only depends on which quantile bin each value falls in. For
    # log-normal data, multiplying by k is a shift of ln(k) / sigma standard
    # deviations in log space, so PSI ~ (ln(k) / sigma)^2.
    sigma = 0.8
    spec = (LogNormalFeature("x", mean_log=10.0, sigma=sigma),)
    reference = _profile(generate_dataset(spec, N, np.random.default_rng(6))["x"])
    current = generate_dataset(spec, N, np.random.default_rng(7))["x"].to_numpy()

    assert not numeric_drift(reference, current).drift_detected
    result = numeric_drift(reference, current * factor)
    assert result.psi == pytest.approx((math.log(factor) / sigma) ** 2, rel=0.35)
    assert result.drift_detected is detected


# --- null behaviour over repeated trials (ML_MONITORING_DESIGN.md section 10) --


def test_false_positive_rates_under_no_drift() -> None:
    n, trials = 1_000, 200
    flags, ks_rejections = 0, 0
    for trial in range(trials):
        reference = _profile(_normal(n, 10_000 + trial)["x"])
        result = numeric_drift(reference, _normal(n, 20_000 + trial)["x"].to_numpy())
        flags += result.drift_detected
        ks_rejections += result.ks_p_value < 0.05

    # PSI noise at n=1000 is ~0.018, far below 0.2: no false alarms expected.
    assert flags == 0
    # KS p-values are uniform under the null, so ~5% fall below 0.05. With 200
    # trials the binomial standard deviation is ~1.5%; allow about +-3 sd.
    assert 1 <= ks_rejections <= 20


# --- result contents, config and validation -------------------------------------


def test_result_contents(reference: NumericProfile) -> None:
    current = _normal(300, 8)["x"].to_numpy()

    result = numeric_drift(reference, current)

    assert result.reference_count == N
    assert result.reference_sample_size == N
    assert result.current_count == 300
    assert result.n_bins == len(reference.bin_counts)
    assert sum(result.reference_proportions) == pytest.approx(1.0)
    assert sum(result.current_proportions) == pytest.approx(1.0)
    assert result.threshold == 0.2
    assert NumericDriftResult.model_validate_json(result.model_dump_json()) == result


def test_ks_uses_the_capped_reference_sample() -> None:
    reference = _profile(_normal(N, 0)["x"], ProfileConfig(sample_size=500))

    result = numeric_drift(reference, _normal(N, 1)["x"].to_numpy())

    assert result.reference_count == N
    assert result.reference_sample_size == 500


def test_custom_threshold(reference: NumericProfile) -> None:
    current = shift_mean(_normal(N, 9), "x", 0.25)["x"].to_numpy()

    default = numeric_drift(reference, current)
    strict = numeric_drift(reference, current, NumericDriftConfig(psi_threshold=0.02))

    assert not default.drift_detected
    assert strict.drift_detected
    assert strict.psi == default.psi


def test_constant_reference_column() -> None:
    # A single integer value is inferred as an integer-coded categorical, so
    # the numeric type is set explicitly.
    text = read_csv_text(pd.Series([5.0] * 200).to_frame("x").to_csv(index=False).encode())
    schema = infer_schema(text, overrides={"x": LogicalType.NUMERIC})
    reference = build_reference_profile(text, schema).features["x"]
    assert isinstance(reference, NumericProfile) and reference.is_constant

    assert not numeric_drift(reference, np.full(50, 5.0)).drift_detected
    changed = numeric_drift(reference, np.full(50, 6.0))
    assert changed.drift_detected
    # Not defined for a constant reference (division by sd = 0).
    assert changed.wasserstein_normalized is None


def test_results_are_deterministic(reference: NumericProfile) -> None:
    current = _normal(N, 11)["x"].to_numpy()

    assert numeric_drift(reference, current) == numeric_drift(reference, current)


@pytest.mark.parametrize(
    ("current", "message"),
    [
        ([], "empty"),
        ([1.0, math.nan], "finite"),
        ([1.0, math.inf], "finite"),
        ([[1.0, 2.0]], "one-dimensional"),
    ],
)
def test_invalid_current_values_are_rejected(
    reference: NumericProfile, current: list[object], message: str
) -> None:
    with pytest.raises(ValueError, match=message):
        numeric_drift(reference, current)  # type: ignore[arg-type]


@pytest.mark.parametrize(
    "kwargs", [{"psi_threshold": 0.0}, {"psi_epsilon": 0.0}, {"psi_epsilon": 0.5}]
)
def test_invalid_config_is_rejected(kwargs: dict[str, float]) -> None:
    with pytest.raises(ValidationError):
        NumericDriftConfig(**kwargs)


# --- normalised Wasserstein (ML_MONITORING_DESIGN.md section 3.3) ---------------


@pytest.mark.parametrize("shift", [0.0, 0.5, 1.0, 2.0])
def test_wasserstein_equals_shift_in_reference_sds(reference: NumericProfile, shift: float) -> None:
    # For a pure location shift, W1 equals the shift; divided by the reference
    # sd (1 here) it reads "moved by `shift` standard deviations".
    current = shift_mean(_normal(N, 13), "x", shift)["x"].to_numpy()

    result = numeric_drift(reference, current)

    assert result.wasserstein_normalized is not None
    assert result.wasserstein_normalized == pytest.approx(shift, abs=0.05)


def test_wasserstein_is_unit_free() -> None:
    base = _normal(N, 14)["x"]
    in_metres = _profile(base * 1.0)
    in_millimetres = _profile(base * 1000.0)
    current = shift_mean(_normal(N, 15), "x", 0.5)["x"]

    a = numeric_drift(in_metres, (current * 1.0).to_numpy()).wasserstein_normalized
    b = numeric_drift(in_millimetres, (current * 1000.0).to_numpy()).wasserstein_normalized

    assert a is not None and b is not None
    assert a == pytest.approx(b, rel=1e-6)


def test_wasserstein_matches_scipy(reference: NumericProfile) -> None:
    current = _normal(700, 16)["x"].to_numpy()

    result = numeric_drift(reference, current)

    expected = wasserstein_distance(np.asarray(reference.sample), current) / reference.std
    assert result.wasserstein_normalized == pytest.approx(expected)
