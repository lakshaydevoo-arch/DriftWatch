"""POST /v1/profile: build a reference profile from an uploaded CSV."""

from typing import Annotated

from fastapi import APIRouter, File, Form, Request, UploadFile
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from driftwatch_analytics.api.errors import ApiError, pydantic_errors
from driftwatch_analytics.api.models import IssueOut
from driftwatch_analytics.api.uploads import read_csv_upload
from driftwatch_analytics.profiling import (
    ProfileConfig,
    ProfileError,
    ReferenceProfile,
    build_reference_profile,
)
from driftwatch_analytics.schema import LogicalType, TaskType, infer_schema
from driftwatch_analytics.settings import Settings

router = APIRouter(tags=["profiling"])


class ProfileOptions(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    overrides: dict[str, LogicalType] = Field(
        default_factory=dict, description="Force a logical type for named columns"
    )
    exclude: list[str] = Field(default_factory=list, description="Columns not to monitor")
    task_type: TaskType | None = Field(
        default=None, description="The model's task; required with prediction_column"
    )
    prediction_column: str | None = Field(
        default=None, description="The model's output column, monitored as prediction drift"
    )
    target_column: str | None = Field(
        default=None, description="Ground-truth label column; kept, not monitored for drift"
    )
    profile: ProfileConfig = ProfileConfig()


class ProfileResponse(BaseModel):
    profile: ReferenceProfile
    issues: list[IssueOut]


@router.post(
    "/v1/profile",
    responses={413: {"description": "Upload too large"}, 422: {"description": "Invalid input"}},
)
def create_profile(
    request: Request,
    file: Annotated[UploadFile, File(description="Reference dataset, UTF-8 CSV with header")],
    options: Annotated[
        str | None, Form(description="JSON-encoded ProfileOptions (optional)")
    ] = None,
) -> ProfileResponse:
    """Infer the schema of a reference dataset and build its reference profile.

    The response's ``profile`` is what the caller stores and later sends to
    ``/v1/drift``.
    """
    settings: Settings = request.app.state.settings
    try:
        parsed_options = (
            ProfileOptions.model_validate_json(options) if options else ProfileOptions()
        )
    except ValidationError as exc:
        raise ApiError(
            422, "invalid-options", "Invalid options", "options are invalid", pydantic_errors(exc)
        ) from exc

    text = read_csv_upload(file, settings.max_upload_bytes, "file")
    if len(text) == 0:
        raise ApiError(422, "empty-dataset", "Empty dataset", "the reference CSV has no data rows")
    try:
        schema = infer_schema(
            text,
            overrides=parsed_options.overrides,
            exclude=parsed_options.exclude,
            task_type=parsed_options.task_type,
            prediction_column=parsed_options.prediction_column,
            target_column=parsed_options.target_column,
        )
    except ValueError as exc:
        raise ApiError(422, "invalid-options", "Invalid options", str(exc)) from exc
    try:
        profile = build_reference_profile(text, schema, parsed_options.profile)
    except ProfileError as exc:
        raise ApiError(422, "unprofilable-dataset", "Dataset cannot be profiled", str(exc)) from exc

    return ProfileResponse(
        profile=profile, issues=[IssueOut.from_issue(issue) for issue in profile.issues]
    )
