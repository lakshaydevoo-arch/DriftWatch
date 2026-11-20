"""Drift configuration and result models."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class NumericDriftConfig(_Frozen):
    # Rule-of-thumb threshold (ML_MONITORING_DESIGN.md section 3.1). Not a
    # significance level.
    psi_threshold: float = Field(default=0.2, gt=0)
    # Replaces zero bin proportions before taking logs.
    psi_epsilon: float = Field(default=1e-4, gt=0, lt=0.01)


class NumericDriftResult(_Frozen):
    kind: Literal["numeric"] = "numeric"
    method: Literal["psi"] = "psi"
    psi: float = Field(ge=0)
    threshold: float
    drift_detected: bool = Field(description="Set by PSI only (the primary method)")
    # Reported alongside PSI. With large samples tiny shifts give p ~ 0, so the
    # p-value is informative but does not set the drift flag (section 3.2).
    ks_statistic: float = Field(ge=0, le=1)
    ks_p_value: float = Field(ge=0, le=1)
    reference_count: int = Field(ge=1)
    reference_sample_size: int = Field(ge=1, description="Reference values used by KS")
    current_count: int = Field(ge=1)
    n_bins: int = Field(ge=2)
    reference_proportions: tuple[float, ...]
    current_proportions: tuple[float, ...]


class CategoricalDriftConfig(_Frozen):
    js_threshold: float = Field(default=0.1, gt=0, le=1)
    # Chi-square approximation needs expected counts >= this in every cell.
    chi2_min_expected: float = Field(default=5.0, gt=0)
    max_reported_unseen: int = Field(default=20, ge=0)


class CategoricalDriftResult(_Frozen):
    kind: Literal["categorical"] = "categorical"
    method: Literal["jensen_shannon"] = "jensen_shannon"
    js_distance: float = Field(ge=0, le=1, description="Jensen-Shannon distance, base 2")
    threshold: float
    drift_detected: bool = Field(description="Set by Jensen-Shannon only (the primary method)")
    # None when the chi-square approximation is invalid even after pooling.
    chi2_statistic: float | None = Field(default=None, ge=0)
    chi2_p_value: float | None = Field(default=None, ge=0, le=1)
    chi2_dof: int | None = Field(default=None, ge=1)
    chi2_pooled: tuple[str | None, ...] = Field(
        default=(), description="Buckets merged so that expected counts are large enough"
    )
    reference_count: int = Field(ge=1)
    current_count: int = Field(ge=1)
    # Bucket labels in a fixed order; None is the "other categories" bucket.
    categories: tuple[str | None, ...]
    reference_proportions: tuple[float, ...]
    current_proportions: tuple[float, ...]
    unseen_categories: tuple[str, ...] = Field(description="Sorted, capped at max_reported_unseen")
    n_unseen_categories: int = Field(ge=0)
    # True when the profile kept only the top categories: a current category
    # outside them may be a rare reference category rather than a new one.
    reference_truncated: bool
    warnings: tuple[str, ...] = ()
