"""Sample-size-aware noise floor (ML_MONITORING_DESIGN.md section 3.4).

The rate tests use repeated seeded trials. Their bounds come from the binomial
distribution of the number of false alarms, not from one lucky seed.
"""

import numpy as np
import pandas as pd
import pytest
from pydantic import ValidationError

from driftwatch_analytics.drift import (
    CategoricalDriftConfig,
    NoiseFloorConfig,
    NumericDriftConfig,
    categorical_drift,
    numeric_drift,
)
from driftwatch_analytics.drift.noise import noise_floor
from driftwatch_analytics.profiling import (
    CategoricalProfile,
    NumericProfile,
    build_reference_profile,
)
from driftwatch_analytics.schema import infer_schema

CATEGORIES = np.array(["a", "b", "c", "d"])
PROBABILITIES = np.array([0.30, 0.25, 0.25, 0.20])


def _numeric_profile(values: np.ndarray) -> NumericProfile:
    text = pd.DataFrame({"x": [repr(float(v)) for v in values]}, dtype="str")
    feature = build_reference_profile(text, infer_schema(text)).features["x"]
    assert isinstance(feature, NumericProfile)
    return feature


def _categorical_profile(values: np.ndarray) -> CategoricalProfile:
    text = pd.DataFrame({"c": values}, dtype="str")
    feature = build_reference_profile(text, infer_schema(text)).features["c"]
    assert isinstance(feature, CategoricalProfile)
    return feature


@pytest.fixture(scope="module")
def numeric_reference() -> NumericProfile:
    return _numeric_profile(np.random.default_rng(0).normal(size=5_000))


@pytest.fixture(scope="module")
def categorical_reference() -> CategoricalProfile:
    rng = np.random.default_rng(0)
    return _categorical_profile(rng.choice(CATEGORIES, size=5_000, p=PROBABILITIES))


# --- the floor itself ---------------------------------------------------------


def test_floor_shrinks_as_the_current_batch_grows(numeric_reference: NumericProfile) -> None:
    rng = np.random.default_rng(1)
    floors = []
    for n in (30, 100, 300, 1_000, 5_000):
        result = numeric_drift(numeric_reference, rng.normal(size=n))
        assert result.noise_floor is not None
        floors.append(result.noise_floor)

    assert floors == sorted(floors, reverse=True)
    assert floors[0] > 0.2  # small batches: floor above the fixed threshold
    assert floors[-1] < 0.02  # large batches: far below it


def test_large_batches_use_the_fixed_threshold(numeric_reference: NumericProfile) -> None:
    result = numeric_drift(numeric_reference, np.random.default_rng(2).normal(size=5_000))

    assert result.effective_threshold == result.threshold == 0.2


def test_small_batches_raise_the_effective_threshold(numeric_reference: NumericProfile) -> None:
    result = numeric_drift(numeric_reference, np.random.default_rng(3).normal(size=50))

    assert result.noise_floor is not None
    assert result.effective_threshold == result.noise_floor > result.threshold


def test_floor_is_deterministic_for_the_same_inputs() -> None:
    config = NoiseFloorConfig()
    p = np.array([0.5, 0.3, 0.2])

    def stat(r: np.ndarray, c: np.ndarray) -> np.ndarray:
        result: np.ndarray = np.abs(r - c).sum(axis=1)
        return result

    assert noise_floor(stat, p, 1_000, 50, config) == noise_floor(stat, p, 1_000, 50, config)
    assert noise_floor(stat, p, 1_000, 50, config) != noise_floor(
        stat, p, 1_000, 50, NoiseFloorConfig(seed=7)
    )


def test_floor_is_zero_for_a_constant_reference() -> None:
    reference = _categorical_profile(np.array(["only"] * 200))

    result = categorical_drift(reference, ["only"] * 40 + ["new"] * 10)

    assert result.noise_floor == 0.0
    assert result.drift_detected


def test_disabling_the_floor_restores_fixed_threshold(numeric_reference: NumericProfile) -> None:
    current = np.random.default_rng(4).normal(size=50)

    result = numeric_drift(numeric_reference, current, NumericDriftConfig(noise_floor=None))

    assert result.noise_floor is None
    assert result.effective_threshold == 0.2
    assert result.drift_detected is (result.psi > 0.2)


# --- false-alarm rates on small batches ------------------------------------------


@pytest.mark.parametrize("n_current", [30, 50, 100])
def test_numeric_false_alarms_stay_low_on_small_batches(
    numeric_reference: NumericProfile, n_current: int
) -> None:
    trials = 300
    rng = np.random.default_rng(100 + n_current)
    flagged_fixed = flagged = 0
    for _ in range(trials):
        current = rng.normal(size=n_current)
        with_floor = numeric_drift(numeric_reference, current)
        flagged += with_floor.drift_detected
        flagged_fixed += with_floor.psi > with_floor.threshold

    # Target ~1% (the 99% quantile). Binomial(300, 0.01) exceeds 9 with
    # probability < 0.1%, so a higher count would mean real mis-calibration.
    assert flagged <= 9
    if n_current <= 50:
        # Without the floor, these sizes give many false alarms (the bug this fixes).
        assert flagged_fixed > 0.3 * trials


@pytest.mark.parametrize("n_current", [30, 50, 100])
def test_categorical_false_alarms_stay_low_on_small_batches(
    categorical_reference: CategoricalProfile, n_current: int
) -> None:
    trials = 300
    rng = np.random.default_rng(200 + n_current)
    flagged_fixed = flagged = 0
    for _ in range(trials):
        current = rng.choice(CATEGORIES, size=n_current, p=PROBABILITIES).tolist()
        result = categorical_drift(categorical_reference, current)
        flagged += result.drift_detected
        flagged_fixed += result.js_distance > result.threshold

    assert flagged <= 9
    if n_current <= 50:
        assert flagged_fixed > 0.3 * trials


# --- detection power is kept where the data supports it -------------------------


@pytest.mark.parametrize(("n_current", "min_rate"), [(50, 0.7), (100, 0.95), (500, 0.99)])
def test_one_sd_shift_still_detected(
    numeric_reference: NumericProfile, n_current: int, min_rate: float
) -> None:
    rng = np.random.default_rng(300 + n_current)
    trials = 100
    detected = sum(
        numeric_drift(numeric_reference, rng.normal(loc=1.0, size=n_current)).drift_detected
        for _ in range(trials)
    )

    assert detected >= min_rate * trials


def test_strong_category_shift_still_detected_at_50_rows(
    categorical_reference: CategoricalProfile,
) -> None:
    rng = np.random.default_rng(400)
    shifted = np.array([0.10, 0.10, 0.40, 0.40])
    trials = 100
    detected = sum(
        categorical_drift(
            categorical_reference, rng.choice(CATEGORIES, size=50, p=shifted).tolist()
        ).drift_detected
        for _ in range(trials)
    )

    assert detected >= 0.9 * trials


def test_moderate_shift_detection_is_unchanged_for_large_batches(
    numeric_reference: NumericProfile,
) -> None:
    current = np.random.default_rng(5).normal(loc=0.5, size=2_000)

    with_floor = numeric_drift(numeric_reference, current)
    without = numeric_drift(numeric_reference, current, NumericDriftConfig(noise_floor=None))

    assert with_floor.drift_detected == without.drift_detected is True


# --- config validation ------------------------------------------------------------


@pytest.mark.parametrize(
    "kwargs", [{"quantile": 0.5}, {"quantile": 1.0}, {"n_simulations": 10}, {"seed": -1}]
)
def test_invalid_noise_floor_config_is_rejected(kwargs: dict[str, float]) -> None:
    with pytest.raises(ValidationError):
        NoiseFloorConfig(**kwargs)  # type: ignore[arg-type]


def test_configs_accept_noise_floor_override() -> None:
    custom = NoiseFloorConfig(quantile=0.999, n_simulations=2_000)

    assert NumericDriftConfig(noise_floor=custom).noise_floor == custom
    assert CategoricalDriftConfig(noise_floor=None).noise_floor is None
