"""Strict CSV loading.

Every cell is loaded as text and type parsing is done by DriftWatch itself
(see ``parsing.py``), so that unparseable values can be counted instead of
being silently coerced by a CSV library.

Python's ``csv`` module is used instead of ``pandas.read_csv`` because pandas
silently pads short rows with empty values and renames duplicate or blank
headers (``a.1``, ``Unnamed: 1``), which would hide malformed input. The cost
is speed: this reader is pure Python, which is acceptable for the v1 upload
size cap.
"""

import csv
import io

import pandas as pd


class CsvFormatError(ValueError):
    """The input is not a well-formed CSV file that DriftWatch can process."""


def read_csv_text(data: bytes) -> pd.DataFrame:
    """Parse UTF-8 CSV bytes into a DataFrame whose cells are all strings.

    The first row is the header. A UTF-8 byte-order mark is accepted.
    Missing values are *not* interpreted here; empty cells stay ``""``.

    Raises:
        CsvFormatError: on invalid UTF-8, an empty file, blank or duplicate
            column names, or rows whose field count differs from the header.
    """
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise CsvFormatError(f"file is not valid UTF-8 (byte offset {exc.start})") from exc

    try:
        rows = list(csv.reader(io.StringIO(text, newline=""), strict=True))
    except csv.Error as exc:
        raise CsvFormatError(f"malformed CSV: {exc}") from exc

    if not rows:
        raise CsvFormatError("file is empty; a header row is required")
    header, body = rows[0], rows[1:]
    _check_header(header)

    width = len(header)
    for line_number, row in enumerate(body, start=2):
        if len(row) != width:
            raise CsvFormatError(f"line {line_number}: expected {width} fields, found {len(row)}")

    columns = {name: [row[i] for row in body] for i, name in enumerate(header)}
    return pd.DataFrame(columns, dtype="str")


def _check_header(header: list[str]) -> None:
    blank = [i + 1 for i, name in enumerate(header) if not name.strip()]
    if blank:
        raise CsvFormatError(f"blank column name at position(s) {blank}")
    duplicates = sorted({name for name in header if header.count(name) > 1})
    if duplicates:
        raise CsvFormatError(f"duplicate column names: {duplicates}")
