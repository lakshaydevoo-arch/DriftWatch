"""ASGI application entry point.

Run locally with::

    uvicorn driftwatch_analytics.main:app --reload
"""

from fastapi import FastAPI

from driftwatch_analytics._version import ENGINE_VERSION
from driftwatch_analytics.api import health


def create_app() -> FastAPI:
    """Build the FastAPI application.

    A factory (rather than only a module-level object) lets tests create a
    fresh, isolated app instance.
    """
    app = FastAPI(
        title="DriftWatch Analytics Engine",
        version=ENGINE_VERSION,
        description="Stateless statistical engine used by the DriftWatch backend.",
    )
    app.include_router(health.router)
    return app


app = create_app()
