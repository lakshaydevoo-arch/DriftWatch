"""POST /v1/drift: compare a current batch with a stored reference profile."""

from typing import Annotated

from fastapi import APIRouter, File, Form, Request, UploadFile
from pydantic import BaseModel, Field, ValidationError

from driftwatch_analytics._version import ENGINE_VERSION
from driftwatch_analytics.api.errors import ApiError, pydantic_errors
from driftwatch_analytics.api.models import IssueOut
from driftwatch_analytics.api.uploads import read_csv_upload, read_upload
from driftwatch_analytics.drift import (
    DriftConfig,
    FeatureDriftResult,
    FeatureStatus,
    evaluate_batch,
)
from driftwatch_analytics.profiling import ReferenceProfile
from driftwatch_analytics.schema import ColumnRole, Severity, validate_batch
from driftwatch_analytics.settings import Settings

router = APIRouter(tags=["drift"])


class DatasetSummary(BaseModel):
    """Input-feature counts; the prediction column is reported separately."""

    n_features: int
    n_evaluated: int
    n_insufficient_data: int
    n_drifted: int
    n_with_quality_flags: int
    # Share of *evaluated* input features with drift; None if none could be evaluated.
    drift_share: float | None = Field(default=None, ge=0, le=1)
    dataset_drift: bool | None = None
    # Drift of the model's prediction column (ML_MONITORING_DESIGN.md section 5).
    # None if the profile has no prediction column or it had too little data.
    prediction_drift: bool | None = None


class DriftResponse(BaseModel):
    engine_version: str
    profile_engine_version: str
    config: DriftConfig = Field(description="Full configuration used, for reproducibility")
    n_rows: int
    summary: DatasetSummary
    features: list[FeatureDriftResult]
    issues: list[IssueOut]
    warnings: list[str]


def summarise(results: tuple[FeatureDriftResult, ...], config: DriftConfig) -> DatasetSummary:
    inputs = [r for r in results if r.role is ColumnRole.FEATURE]
    evaluated = [r for r in inputs if r.status is FeatureStatus.EVALUATED]
    drifted = sum(r.drift_detected for r in evaluated)
    share = drifted / len(evaluated) if evaluated else None
    predictions = [
        r
        for r in results
        if r.role is ColumnRole.PREDICTION and r.status is FeatureStatus.EVALUATED
    ]
    return DatasetSummary(
        n_features=len(inputs),
        n_evaluated=len(evaluated),
        n_insufficient_data=len(inputs) - len(evaluated),
        n_drifted=drifted,
        n_with_quality_flags=sum(bool(r.flags) for r in inputs),
        drift_share=share,
        dataset_drift=None if share is None else share >= config.dataset_drift_share,
        prediction_drift=predictions[0].drift_detected if predictions else None,
    )


@router.post(
    "/v1/drift",
    responses={413: {"description": "Upload too large"}, 422: {"description": "Invalid input"}},
)
def detect_drift(
    request: Request,
    profile: Annotated[
        UploadFile, File(description="Reference profile JSON, as returned by /v1/profile")
    ],
    current: Annotated[UploadFile, File(description="Current batch, UTF-8 CSV with header")],
    config: Annotated[str | None, Form(description="JSON-encoded DriftConfig (optional)")] = None,
) -> DriftResponse:
    """Validate the current batch against the profile's schema and evaluate drift.

    The profile is sent as a file part because profiles can exceed the 1 MB
    limit that applies to ordinary form fields.
    """
    settings: Settings = request.app.state.settings
    try:
        drift_config = DriftConfig.model_validate_json(config) if config else DriftConfig()
    except ValidationError as exc:
        raise ApiError(
            422,
            "invalid-config",
            "Invalid configuration",
            "config is invalid",
            pydantic_errors(exc),
        ) from exc
    try:
        reference = ReferenceProfile.model_validate_json(
            read_upload(profile, settings.max_upload_bytes, "profile")
        )
    except ValidationError as exc:
        raise ApiError(
            422,
            "invalid-profile",
            "Invalid profile",
            "the profile is not a valid DriftWatch reference profile",
            pydantic_errors(exc),
        ) from exc

    text = read_csv_upload(current, settings.max_upload_bytes, "current")
    report = validate_batch(text, reference.dataset_schema)
    issues = [IssueOut.from_issue(issue) for issue in report.issues]
    if not report.is_valid:
        raise ApiError(
            422,
            "schema-mismatch",
            "Batch does not match the reference schema",
            "the current batch is missing monitored columns",
            [issue.model_dump(mode="json") for issue in issues if issue.severity is Severity.ERROR],
        )

    results = evaluate_batch(reference, report, drift_config)
    warnings = []
    if reference.engine_version != ENGINE_VERSION:
        warnings.append(
            f"profile was built with engine {reference.engine_version}, running "
            f"{ENGINE_VERSION}; results may differ from a run on the original engine"
        )
    return DriftResponse(
        engine_version=ENGINE_VERSION,
        profile_engine_version=reference.engine_version,
        config=drift_config,
        n_rows=report.n_rows,
        summary=summarise(results, drift_config),
        features=list(results),
        issues=issues,
        warnings=warnings,
    )
