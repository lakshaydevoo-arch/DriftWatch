"""Engine version, read from installed package metadata.

The version is recorded with every monitoring run (see ML_MONITORING_DESIGN.md
section 8), so it has a single source of truth: ``pyproject.toml``.
"""

from importlib.metadata import PackageNotFoundError, version

_DISTRIBUTION_NAME = "driftwatch-analytics"


def _read_version() -> str:
    try:
        return version(_DISTRIBUTION_NAME)
    except PackageNotFoundError:
        # Running from a source tree without `pip install -e .`. Make the
        # problem visible instead of reporting a plausible-looking version.
        return "0.0.0+unknown"


ENGINE_VERSION: str = _read_version()
