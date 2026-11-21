"""Runtime settings, read from environment variables (12-factor style)."""

import os
from dataclasses import dataclass

DEFAULT_MAX_UPLOAD_BYTES = 100 * 1024 * 1024  # ARCHITECTURE.md section 8


@dataclass(frozen=True)
class Settings:
    # Maximum size of each uploaded file. Whole files are processed in memory.
    max_upload_bytes: int = DEFAULT_MAX_UPLOAD_BYTES

    def __post_init__(self) -> None:
        if self.max_upload_bytes < 1:
            raise ValueError("max_upload_bytes must be >= 1")

    @classmethod
    def from_env(cls) -> "Settings":
        raw = os.environ.get("DRIFTWATCH_MAX_UPLOAD_BYTES")
        if raw is None:
            return cls()
        try:
            value = int(raw)
        except ValueError:
            raise ValueError(
                f"DRIFTWATCH_MAX_UPLOAD_BYTES must be an integer, got {raw!r}"
            ) from None
        return cls(max_upload_bytes=value)
