"""No-drift controls on distribution shapes that stress the noise floor.

The noise floor models histograms as multinomials, an approximation that is
weakest for skewed, tied, zero-inflated and many-category data. Each case
draws reference and current batches from the *same* distribution, so every
flag is a false alarm.

Bound: the documented small-batch false-alarm rate is about 1-2%
(ML_MONITORING_DESIGN.md section 3.4). With 120 trials and a true rate of 3%
the chance of more than 10 alarms is below 1%, so exceeding 10 indicates real
mis-calibration rather than bad luck. Measured rates while writing these
tests: 0-3.3% at 50 rows, 0-1.3% at 200 and 1,000 rows.
"""

from collections.abc import Callable

import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.drift import categorical_drift, numeric_drift
from driftwatch_analytics.profiling import (
    CategoricalProfile,
    NumericProfile,
    ProfileConfig,
    build_reference_profile,
)
from driftwatch_analytics.schema import LogicalType, infer_schema

REFERENCE_ROWS = 1_000
CURRENT_ROWS = 50  # the hardest case for the noise floor
TRIALS = 120
MAX_ALARMS = 10

Generator = Callable[[np.random.Generator, int], np.ndarray]


def _profile(values: np.ndarray, logical_type: LogicalType) -> NumericProfile | CategoricalProfile:
    text = pd.Series(values).astype("str").to_frame("f")
    schema = infer_schema(text, overrides={"f": logical_type})
    return build_reference_profile(text, schema, ProfileConfig(sample_size=200)).features["f"]


NUMERIC_SHAPES: dict[str, Generator] = {
    "log-normal, sigma 1.5 (very skewed)": lambda r, n: r.lognormal(0.0, 1.5, n),
    "student t, 2 df (heavy tails)": lambda r, n: r.standard_t(2, n),
    "bimodal": lambda r, n: np.where(r.random(n) < 0.5, r.normal(-3, 1, n), r.normal(3, 1, n)),
    "poisson counts (many ties)": lambda r, n: r.poisson(8, n).astype(float),
    "70% zeros (zero-inflated)": lambda r, n: np.where(r.random(n) < 0.7, 0.0, r.exponential(5, n)),
    "uniform rounded to 0.1": lambda r, n: np.round(r.uniform(0, 3, n), 1),
}

_RARE_TAIL = np.r_[np.full(5, 0.12), np.full(15, 0.4 / 15)]
CATEGORICAL_SHAPES: dict[str, tuple[np.ndarray, np.ndarray]] = {
    "2 categories, 95/5": (np.array(["a", "b"]), np.array([0.95, 0.05])),
    "20 categories, 15 rare": (np.array([f"c{i:02d}" for i in range(20)]), _RARE_TAIL),
    "60 categories, uniform": (np.array([f"c{i:02d}" for i in range(60)]), np.full(60, 1 / 60)),
}


@pytest.mark.parametrize("shape", list(NUMERIC_SHAPES))
def test_numeric_no_drift_false_alarms(shape: str) -> None:
    generate = NUMERIC_SHAPES[shape]
    rng = np.random.default_rng([1, list(NUMERIC_SHAPES).index(shape)])
    alarms = 0
    for _ in range(TRIALS):
        reference = _profile(generate(rng, REFERENCE_ROWS), LogicalType.NUMERIC)
        assert isinstance(reference, NumericProfile)
        alarms += numeric_drift(reference, generate(rng, CURRENT_ROWS)).drift_detected

    assert alarms <= MAX_ALARMS, f"{shape}: {alarms}/{TRIALS} false alarms"


@pytest.mark.parametrize("shape", list(CATEGORICAL_SHAPES))
def test_categorical_no_drift_false_alarms(shape: str) -> None:
    categories, probabilities = CATEGORICAL_SHAPES[shape]
    rng = np.random.default_rng([2, list(CATEGORICAL_SHAPES).index(shape)])
    alarms = 0
    for _ in range(TRIALS):
        reference = _profile(
            rng.choice(categories, REFERENCE_ROWS, p=probabilities), LogicalType.CATEGORICAL
        )
        assert isinstance(reference, CategoricalProfile)
        current = rng.choice(categories, CURRENT_ROWS, p=probabilities).tolist()
        alarms += categorical_drift(reference, current).drift_detected

    assert alarms <= MAX_ALARMS, f"{shape}: {alarms}/{TRIALS} false alarms"
