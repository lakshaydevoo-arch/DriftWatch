"""ASGI application entry point.

Run locally with::

    uvicorn driftwatch_analytics.main:app --reload
"""

from fastapi import FastAPI

from driftwatch_analytics._version import ENGINE_VERSION
from driftwatch_analytics.api import drift, health, performance, profile
from driftwatch_analytics.api.errors import install_error_handlers
from driftwatch_analytics.api.uploads import body_size_limiter
from driftwatch_analytics.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the FastAPI application.

    A factory (rather than only a module-level object) lets tests create a
    fresh, isolated app instance with their own settings.
    """
    settings = settings or Settings.from_env()
    app = FastAPI(
        title="DriftWatch Analytics Engine",
        version=ENGINE_VERSION,
        description="Stateless statistical engine used by the DriftWatch backend.",
    )
    app.state.settings = settings
    install_error_handlers(app)
    # /v1/drift uploads two files (profile + current batch).
    app.middleware("http")(body_size_limiter(settings.max_upload_bytes, max_files=2))
    app.include_router(health.router)
    app.include_router(profile.router)
    app.include_router(drift.router)
    app.include_router(performance.router)
    return app


app = create_app()
