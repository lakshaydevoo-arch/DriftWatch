"""RFC 9457 problem details for every error response.

All errors use ``application/problem+json`` with a stable ``type`` URN, so
the backend can branch on the error kind without parsing messages.
"""

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

PROBLEM_MEDIA_TYPE = "application/problem+json"
_TYPE_PREFIX = "urn:driftwatch:problem:"
# Bound the size of error responses (a corrupted profile can produce
# thousands of validation errors).
MAX_REPORTED_ERRORS = 20


class Problem(BaseModel):
    type: str
    title: str
    status: int
    detail: str
    errors: list[dict[str, Any]] | None = None


class ApiError(Exception):
    def __init__(
        self,
        status: int,
        kind: str,
        title: str,
        detail: str,
        errors: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(detail)
        self.problem = Problem(
            type=_TYPE_PREFIX + kind, title=title, status=status, detail=detail, errors=errors
        )


def pydantic_errors(exc: ValidationError) -> list[dict[str, Any]]:
    """Compact, JSON-safe error list. Inputs are omitted: they can be huge."""
    return [
        {"loc": [str(part) for part in error["loc"]], "msg": error["msg"]}
        for error in exc.errors(include_url=False, include_context=False, include_input=False)[
            :MAX_REPORTED_ERRORS
        ]
    ]


def problem_response(problem: Problem) -> JSONResponse:
    return JSONResponse(
        problem.model_dump(exclude_none=True),
        status_code=problem.status,
        media_type=PROBLEM_MEDIA_TYPE,
    )


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return problem_response(exc.problem)

    @app.exception_handler(RequestValidationError)
    async def _request_validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        errors = [
            {"loc": [str(part) for part in error["loc"]], "msg": error["msg"]}
            for error in exc.errors()[:MAX_REPORTED_ERRORS]
        ]
        return problem_response(
            Problem(
                type=_TYPE_PREFIX + "invalid-request",
                title="Invalid request",
                status=422,
                detail="The request is missing required parts or has invalid fields.",
                errors=errors,
            )
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        return problem_response(
            Problem(
                type="about:blank",
                title=str(exc.detail),
                status=exc.status_code,
                detail=str(exc.detail),
            )
        )
