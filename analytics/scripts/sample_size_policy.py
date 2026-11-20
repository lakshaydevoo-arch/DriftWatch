"""Reproduce the sample-size policy table in ML_MONITORING_DESIGN.md section 3.4.

For each current batch size it reports the share of batches flagged as
drifted, with the fixed threshold only and with the noise floor, under no
drift and under real drift. It uses the project's own drift functions, and
every trial draws a fresh 5,000-row reference, so reference sampling noise is
included.

    python scripts/sample_size_policy.py            # default: 200 trials
    python scripts/sample_size_policy.py --trials 500

Seeded: the same arguments always print the same table.
"""

import argparse

import numpy as np
import pandas as pd

from driftwatch_analytics.drift import (
    CategoricalDriftConfig,
    NumericDriftConfig,
    categorical_drift,
    numeric_drift,
)
from driftwatch_analytics.profiling import (
    CategoricalProfile,
    NumericProfile,
    ProfileConfig,
    build_reference_profile,
)
from driftwatch_analytics.schema import LogicalType, infer_schema

REFERENCE_ROWS = 5_000
BATCH_SIZES = (30, 50, 100, 200, 500, 1_000)
CATEGORIES = np.array(["a", "b", "c", "d"])
BASE = np.array([0.30, 0.25, 0.25, 0.20])
NUMERIC_EFFECTS = {"no drift": 0.0, "0.5 sd shift": 0.5, "1 sd shift": 1.0}
CATEGORICAL_EFFECTS = {
    "no drift": BASE,
    "mild shift": np.array([0.20, 0.25, 0.25, 0.30]),
    "strong shift": np.array([0.10, 0.10, 0.40, 0.40]),
}


def _profile(column: pd.Series, logical_type: LogicalType) -> NumericProfile | CategoricalProfile:
    text = column.to_frame("f").astype("str")
    schema = infer_schema(text, overrides={"f": logical_type})
    # Small sample: KS/Wasserstein are not used here and this keeps it fast.
    return build_reference_profile(text, schema, ProfileConfig(sample_size=100)).features["f"]


def run(trials: int, seed: int) -> list[str]:
    rng = np.random.default_rng(seed)
    fixed_numeric = NumericDriftConfig(noise_floor=None)
    fixed_categorical = CategoricalDriftConfig(noise_floor=None)
    counts: dict[tuple[str, str, int], list[int]] = {}

    for _ in range(trials):
        numeric_ref = _profile(pd.Series(rng.normal(size=REFERENCE_ROWS)), LogicalType.NUMERIC)
        categorical_ref = _profile(
            pd.Series(rng.choice(CATEGORIES, size=REFERENCE_ROWS, p=BASE)),
            LogicalType.CATEGORICAL,
        )
        assert isinstance(numeric_ref, NumericProfile)
        assert isinstance(categorical_ref, CategoricalProfile)
        for n in BATCH_SIZES:
            for label, shift in NUMERIC_EFFECTS.items():
                current = rng.normal(loc=shift, size=n)
                a = numeric_drift(numeric_ref, current, fixed_numeric).drift_detected
                b = numeric_drift(numeric_ref, current).drift_detected
                cell = counts.setdefault(("PSI", label, n), [0, 0])
                cell[0] += a
                cell[1] += b
            for label, probabilities in CATEGORICAL_EFFECTS.items():
                labels = rng.choice(CATEGORIES, size=n, p=probabilities).tolist()
                a = categorical_drift(categorical_ref, labels, fixed_categorical).drift_detected
                b = categorical_drift(categorical_ref, labels).drift_detected
                cell = counts.setdefault(("JS", label, n), [0, 0])
                cell[0] += a
                cell[1] += b

    lines = []
    for method, effects in (("PSI", NUMERIC_EFFECTS), ("JS", CATEGORICAL_EFFECTS)):
        lines.append(f"\n{method}: share of batches flagged (fixed threshold -> with noise floor)")
        lines.append("| Rows | " + " | ".join(effects) + " |")
        lines.append("|---" * (len(effects) + 1) + "|")
        for n in BATCH_SIZES:
            cells = []
            for label in effects:
                fixed, floor = counts[(method, label, n)]
                cells.append(f"{fixed / trials:.0%} -> {floor / trials:.0%}")
            lines.append(f"| {n} | " + " | ".join(cells) + " |")
    return lines


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--trials", type=int, default=200)
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args()
    print(f"{args.trials} trials per cell, {REFERENCE_ROWS}-row reference, seed {args.seed}")
    print("\n".join(run(args.trials, args.seed)))


if __name__ == "__main__":
    main()
