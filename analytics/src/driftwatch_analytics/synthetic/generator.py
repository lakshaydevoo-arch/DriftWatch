"""Generate reference-style datasets from feature specs."""

from collections.abc import Sequence

import numpy as np
import pandas as pd

from driftwatch_analytics.synthetic.specs import (
    CategoricalFeature,
    FeatureSpec,
    LogNormalFeature,
    NormalFeature,
)

# A small, clearly synthetic tabular scenario resembling loan-application data:
# symmetric, skewed and categorical features with an uneven category mix.
LOAN_APPLICANT_FEATURES: tuple[FeatureSpec, ...] = (
    NormalFeature("age", mean=40.0, std=12.0),
    LogNormalFeature("income", mean_log=10.5, sigma=0.5),
    NormalFeature("credit_score", mean=650.0, std=70.0),
    CategoricalFeature(
        "region",
        categories=("north", "south", "east", "west"),
        probabilities=(0.30, 0.25, 0.25, 0.20),
    ),
    CategoricalFeature(
        "employment",
        categories=("salaried", "self_employed", "unemployed"),
        probabilities=(0.70, 0.20, 0.10),
    ),
)


def generate_dataset(
    specs: Sequence[FeatureSpec], n_rows: int, rng: np.random.Generator
) -> pd.DataFrame:
    """Draw ``n_rows`` independent rows, one column per spec, in spec order.

    Features are sampled independently of each other (no correlations), which
    matches the univariate scope of v1 drift detection.

    Columns are drawn sequentially from ``rng``, so for a given seed, appending
    a spec leaves earlier columns unchanged, while reordering specs changes them.
    """
    if n_rows < 1:
        raise ValueError(f"n_rows must be >= 1, got {n_rows}")
    if not specs:
        raise ValueError("at least one feature spec is required")
    names = [spec.name for spec in specs]
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise ValueError(f"duplicate feature names: {duplicates}")

    columns: dict[str, pd.Series] = {}
    for spec in specs:
        columns[spec.name] = _sample(spec, n_rows, rng)
    return pd.DataFrame(columns)


def _sample(spec: FeatureSpec, n_rows: int, rng: np.random.Generator) -> pd.Series:
    match spec:
        case NormalFeature(mean=mean, std=std):
            return pd.Series(rng.normal(mean, std, size=n_rows), dtype="float64")
        case LogNormalFeature(mean_log=mean_log, sigma=sigma):
            return pd.Series(rng.lognormal(mean_log, sigma, size=n_rows), dtype="float64")
        case CategoricalFeature(categories=categories, probabilities=probabilities):
            values = rng.choice(np.array(categories, dtype=object), size=n_rows, p=probabilities)
            return pd.Series(values, dtype="str")
