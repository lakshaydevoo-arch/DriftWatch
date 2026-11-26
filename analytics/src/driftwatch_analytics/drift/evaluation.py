"""Per-feature evaluation: drift plus data-quality flags for a validated batch.

This is where validation (schema), the reference profile and the drift
methods meet. Each monitored feature gets exactly one ``FeatureDriftResult``.

``drift_detected`` only reflects a change in the *distribution of valid
values*. Data-quality problems (more missing values, unparseable values,
new categories) are reported as separate ``flags`` so they are not confused
with distribution drift (ML_MONITORING_DESIGN.md sections 1 and 7).
"""

from enum import StrEnum
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from driftwatch_analytics.drift.categorical import categorical_drift
from driftwatch_analytics.drift.models import (
    CategoricalDriftConfig,
    CategoricalDriftResult,
    NumericDriftConfig,
    NumericDriftResult,
)
from driftwatch_analytics.drift.numeric import numeric_drift
from driftwatch_analytics.profiling.categories import category_labels
from driftwatch_analytics.profiling.models import (
    CategoricalProfile,
    NumericProfile,
    ReferenceProfile,
)
from driftwatch_analytics.schema.types import ColumnRole, LogicalType
from driftwatch_analytics.schema.validation import BatchValidationReport


class FeatureStatus(StrEnum):
    EVALUATED = "evaluated"
    # Too few valid current values for a meaningful drift verdict.
    INSUFFICIENT_DATA = "insufficient_data"


class QualityFlag(StrEnum):
    SCHEMA_VIOLATION = "schema_violation"
    MISSING_RATE_CHANGE = "missing_rate_change"
    CONSTANT_IN_REFERENCE = "constant_in_reference"
    UNSEEN_CATEGORIES = "unseen_categories"


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class DriftConfig(_Frozen):
    numeric: NumericDriftConfig = NumericDriftConfig()
    categorical: CategoricalDriftConfig = CategoricalDriftConfig()
    # Below this, no drift verdict at all. Above it, the per-method noise
    # floor (ML_MONITORING_DESIGN.md section 3.4) keeps small batches from
    # producing false alarms.
    min_valid_count: int = Field(default=30, ge=1)
    max_missing_rate_change: float = Field(default=0.05, ge=0, le=1)
    # Dataset-level alert when at least this share of evaluated features drift
    # (ML_MONITORING_DESIGN.md section 9). A heuristic, not a corrected test.
    dataset_drift_share: float = Field(default=0.5, gt=0, le=1)


DriftResult = Annotated[NumericDriftResult | CategoricalDriftResult, Field(discriminator="kind")]


class FeatureDriftResult(_Frozen):
    name: str
    logical_type: LogicalType
    # FEATURE (an input) or PREDICTION (the model's output: prediction drift).
    role: ColumnRole = ColumnRole.FEATURE
    status: FeatureStatus
    drift_detected: bool
    flags: tuple[QualityFlag, ...]
    valid_count_current: int = Field(ge=0)
    missing_rate_reference: float = Field(ge=0, le=1)
    missing_rate_current: float | None = Field(default=None, ge=0, le=1)
    invalid_rate_current: float | None = Field(default=None, ge=0, le=1)
    drift: DriftResult | None = None


def evaluate_batch(
    profile: ReferenceProfile,
    report: BatchValidationReport,
    config: DriftConfig | None = None,
) -> tuple[FeatureDriftResult, ...]:
    """Evaluate every monitored feature, in schema order.

    ``report`` must come from ``validate_batch(current, profile.dataset_schema)``.

    Raises:
        ValueError: if the report is invalid (a monitored column is missing) or
            does not cover the profile's features.
    """
    config = config or DriftConfig()
    if not report.is_valid:
        raise ValueError("batch is invalid; reject it before evaluating drift")
    missing = sorted(set(profile.features) - set(report.parsed))
    if missing:
        raise ValueError(
            f"report lacks profiled features {missing}; validate the batch against "
            "the profile's schema"
        )
    return tuple(
        _evaluate_feature(column.name, column.logical_type, column.role, profile, report, config)
        for column in profile.dataset_schema.columns
        if column.monitored
    )


def _evaluate_feature(
    name: str,
    logical_type: LogicalType,
    role: ColumnRole,
    profile: ReferenceProfile,
    report: BatchValidationReport,
    config: DriftConfig,
) -> FeatureDriftResult:
    reference = profile.features[name]
    validation = report.column(name)
    parsed = report.parsed[name]
    flags: list[QualityFlag] = []

    missing_rate_reference = reference.n_missing / profile.n_rows
    missing_rate_current = validation.missing_rate
    if validation.schema_violation:
        flags.append(QualityFlag.SCHEMA_VIOLATION)
    if (
        missing_rate_current is not None
        and abs(missing_rate_current - missing_rate_reference) > config.max_missing_rate_change
    ):
        flags.append(QualityFlag.MISSING_RATE_CHANGE)
    if _is_constant(reference):
        flags.append(QualityFlag.CONSTANT_IN_REFERENCE)

    valid = parsed.values.dropna()
    status = FeatureStatus.EVALUATED
    drift: NumericDriftResult | CategoricalDriftResult | None = None
    if valid.size < config.min_valid_count:
        status = FeatureStatus.INSUFFICIENT_DATA
    elif isinstance(reference, NumericProfile):
        if logical_type is not LogicalType.NUMERIC:
            raise ValueError(f"{name}: numeric profile but schema type {logical_type}")
        drift = numeric_drift(reference, valid.to_numpy(dtype=float), config.numeric)
    else:
        if logical_type not in (LogicalType.CATEGORICAL, LogicalType.BOOLEAN):
            raise ValueError(f"{name}: categorical profile but schema type {logical_type}")
        labels = category_labels(parsed.values, logical_type)
        drift = categorical_drift(reference, labels.tolist(), config.categorical)
        if drift.n_unseen_categories:
            flags.append(QualityFlag.UNSEEN_CATEGORIES)

    return FeatureDriftResult(
        name=name,
        logical_type=logical_type,
        role=role,
        status=status,
        drift_detected=drift is not None and drift.drift_detected,
        flags=tuple(flags),
        valid_count_current=int(valid.size),
        missing_rate_reference=missing_rate_reference,
        missing_rate_current=missing_rate_current,
        invalid_rate_current=validation.invalid_rate,
        drift=drift,
    )


def _is_constant(reference: NumericProfile | CategoricalProfile) -> bool:
    if isinstance(reference, NumericProfile):
        return reference.is_constant
    return len(reference.counts) == 1 and reference.other_count == 0
