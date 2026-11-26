"""POST /v1/performance: join ground-truth labels to a batch's predictions."""

from typing import Annotated

from fastapi import APIRouter, File, Form, Request, UploadFile
from pydantic import BaseModel, ValidationError

from driftwatch_analytics._version import ENGINE_VERSION
from driftwatch_analytics.api.errors import ApiError, pydantic_errors
from driftwatch_analytics.api.uploads import read_csv_upload, read_upload
from driftwatch_analytics.performance import (
    JoinError,
    JoinReport,
    PerformanceConfig,
    PerformanceMetrics,
    compute_metrics,
    join_labels,
)
from driftwatch_analytics.profiling import ReferenceProfile
from driftwatch_analytics.settings import Settings

router = APIRouter(tags=["performance"])


class PerformanceResponse(BaseModel):
    engine_version: str
    profile_engine_version: str
    config: PerformanceConfig
    join: JoinReport
    metrics: PerformanceMetrics


@router.post(
    "/v1/performance",
    responses={413: {"description": "Upload too large"}, 422: {"description": "Invalid input"}},
)
def evaluate_performance(
    request: Request,
    profile: Annotated[
        UploadFile, File(description="Reference profile JSON, as returned by /v1/profile")
    ],
    current: Annotated[UploadFile, File(description="The batch the labels belong to (CSV)")],
    labels: Annotated[
        UploadFile, File(description="Ground truth: record id and target columns (CSV)")
    ],
    config: Annotated[
        str | None, Form(description="JSON-encoded PerformanceConfig (optional)")
    ] = None,
) -> PerformanceResponse:
    """Join labels to the batch by record id and compute metrics on the joined rows."""
    settings: Settings = request.app.state.settings
    try:
        performance_config = (
            PerformanceConfig.model_validate_json(config) if config else PerformanceConfig()
        )
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
    batch = read_csv_upload(current, settings.max_upload_bytes, "current")
    label_rows = read_csv_upload(labels, settings.max_upload_bytes, "labels")
    try:
        result = join_labels(batch, label_rows, reference.dataset_schema)
    except JoinError as exc:
        raise ApiError(422, exc.kind, "Labels cannot be joined", str(exc), exc.errors) from exc
    return PerformanceResponse(
        engine_version=ENGINE_VERSION,
        profile_engine_version=reference.engine_version,
        config=performance_config,
        join=result.report,
        metrics=compute_metrics(batch, result.joined, reference.dataset_schema, performance_config),
    )
