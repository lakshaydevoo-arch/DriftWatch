"""Bounded reading of uploaded files and request bodies."""

from collections.abc import Awaitable, Callable

import pandas as pd
from fastapi import Request, Response, UploadFile

from driftwatch_analytics.api.errors import ApiError, Problem, problem_response
from driftwatch_analytics.schema import CsvFormatError, read_csv_text

# Multipart framing and small form fields on top of the files themselves.
_FORM_OVERHEAD_BYTES = 1024 * 1024


def read_upload(upload: UploadFile, max_bytes: int, part: str) -> bytes:
    """Read an uploaded file, failing with 413 if it exceeds ``max_bytes``."""
    data = upload.file.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ApiError(
            413,
            "upload-too-large",
            "Upload too large",
            f"'{part}' exceeds the limit of {max_bytes} bytes",
        )
    return data


def body_size_limiter(
    max_bytes_per_file: int, max_files: int
) -> Callable[[Request, Callable[[Request], Awaitable[Response]]], Awaitable[Response]]:
    """Middleware rejecting oversized requests by ``Content-Length`` *before* reading them.

    Without this, the whole body would be received (and spooled to disk)
    before ``read_upload`` could reject it. Requests without a
    ``Content-Length`` (chunked) are still bounded per file by ``read_upload``.
    """
    limit = max_bytes_per_file * max_files + _FORM_OVERHEAD_BYTES

    async def middleware(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        length = request.headers.get("content-length")
        if length is not None and length.isdigit() and int(length) > limit:
            return problem_response(
                Problem(
                    type="urn:driftwatch:problem:upload-too-large",
                    title="Upload too large",
                    status=413,
                    detail=f"request body exceeds the limit of {limit} bytes",
                )
            )
        return await call_next(request)

    return middleware


def read_csv_upload(upload: UploadFile, max_bytes: int, part: str) -> pd.DataFrame:
    """Read and parse an uploaded CSV; size and format errors become problem responses."""
    data = read_upload(upload, max_bytes, part)
    try:
        return read_csv_text(data)
    except CsvFormatError as exc:
        raise ApiError(422, "invalid-csv", "Invalid CSV", f"'{part}': {exc}") from exc
