"""Seeded synthetic datasets with controllable drift.

Used to test drift methods against data where the correct answer is known
(see ML_MONITORING_DESIGN.md section 10). Every function takes an explicit
``numpy.random.Generator`` so results are reproducible and independent calls do
not share hidden global random state.
"""

from driftwatch_analytics.synthetic.drift import (
    inject_missing,
    resample_categories,
    scale_std,
    shift_mean,
)
from driftwatch_analytics.synthetic.generator import LOAN_APPLICANT_FEATURES, generate_dataset
from driftwatch_analytics.synthetic.specs import (
    CategoricalFeature,
    FeatureSpec,
    LogNormalFeature,
    NormalFeature,
)

__all__ = [
    "LOAN_APPLICANT_FEATURES",
    "CategoricalFeature",
    "FeatureSpec",
    "LogNormalFeature",
    "NormalFeature",
    "generate_dataset",
    "inject_missing",
    "resample_categories",
    "scale_std",
    "shift_mean",
]
