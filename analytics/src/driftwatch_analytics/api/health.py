"""Liveness endpoint."""

from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

from driftwatch_analytics._version import ENGINE_VERSION

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: Literal["ok"]
    engine_version: str


@router.get("/health")
def health() -> HealthResponse:
    """Report that the process is serving requests and which engine version is running.

    The backend reads ``engine_version`` to record it with monitoring runs.
    """
    return HealthResponse(status="ok", engine_version=ENGINE_VERSION)
