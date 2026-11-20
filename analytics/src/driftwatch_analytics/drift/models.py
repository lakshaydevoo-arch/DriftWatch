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
