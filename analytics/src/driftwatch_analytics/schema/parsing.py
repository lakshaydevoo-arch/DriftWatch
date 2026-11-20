"""Turning text cells into typed values, counting missing and invalid ones.

Missing and invalid are deliberately different:

- *missing*: the cell is empty or a recognised null token (``MISSING_TOKENS``);
- *invalid*: the cell has content that cannot be parsed as the column's type
  (e.g. ``"abc"`` or ``"1,000"`` in a numeric column).

Both are excluded from distribution tests, but they are reported separately
because they point to different problems.
"""

from dataclasses import dataclass

import numpy as np
import pandas as pd

from driftwatch_analytics.schema.types import LogicalType

# Compared case-insensitively after stripping whitespace.
MISSING_TOKENS: frozenset[str] = frozenset({"", "na", "n/a", "nan", "null", "none"})

TRUE_TOKENS: frozenset[str] = frozenset({"true", "yes", "1"})
FALSE_TOKENS: frozenset[str] = frozenset({"false", "no", "0"})

# Largest magnitude at which every integer is exactly representable as float64.
# Beyond it, "integer-valued" is meaningless and int64 conversion can overflow.
MAX_EXACT_INTEGER = 2**53


@dataclass(frozen=True)
class ParsedColumn:
    """Typed values plus masks. ``values`` is missing wherever either mask is set."""

    values: pd.Series
    missing: np.ndarray
    invalid: np.ndarray

    @property
    def n_missing(self) -> int:
        return int(self.missing.sum())

    @property
    def n_invalid(self) -> int:
        return int(self.invalid.sum())


def normalise(text: pd.Series) -> tuple[pd.Series, np.ndarray]:
    """Strip whitespace and return ``(stripped, missing_mask)``."""
    stripped = text.astype("str").str.strip()
    missing = stripped.str.lower().isin(MISSING_TOKENS).to_numpy(dtype=bool)
    return stripped, missing


def parse_numeric(stripped: pd.Series) -> pd.Series:
    """Parse to float64. Unparseable and non-finite (``inf``) values become NaN."""
    values = pd.to_numeric(stripped, errors="coerce").astype("float64")
    return values.where(np.isfinite(values))


def parse_boolean(stripped: pd.Series) -> pd.Series:
    lowered = stripped.str.lower()
    result = pd.Series(pd.NA, index=stripped.index, dtype="boolean")
    result[lowered.isin(TRUE_TOKENS)] = True
    result[lowered.isin(FALSE_TOKENS)] = False
    return result


def parse_datetime(stripped: pd.Series) -> pd.Series:
    """ISO 8601 only. Ambiguous formats such as ``01/02/2024`` are invalid on purpose."""
    parsed: pd.Series = pd.to_datetime(stripped, format="ISO8601", errors="coerce", utc=True)
    return parsed


def is_integer_valued(numbers: pd.Series) -> pd.Series:
    """Element-wise: finite, a whole number, and small enough to be exact."""
    return numbers.notna() & (numbers % 1 == 0) & (numbers.abs() <= MAX_EXACT_INTEGER)


def canonical_integer_strings(stripped: pd.Series) -> pd.Series:
    """Map integer-valued numbers to their plain form: ``"3.0"`` -> ``"3"``.

    Values that are not integer-valued numbers are returned unchanged.
    """
    numbers = parse_numeric(stripped)
    is_integer = is_integer_valued(numbers)
    result = stripped.copy()
    result[is_integer] = numbers[is_integer].astype("int64").astype("str")
    return result


def parse_column(
    text: pd.Series, logical_type: LogicalType, *, integer_coded: bool = False
) -> ParsedColumn:
    """Parse a text column according to its logical type."""
    stripped, missing = normalise(text)
    present = ~missing

    values: pd.Series
    match logical_type:
        case LogicalType.NUMERIC:
            values = parse_numeric(stripped)
        case LogicalType.BOOLEAN:
            values = parse_boolean(stripped)
        case LogicalType.DATETIME:
            values = parse_datetime(stripped)
        case LogicalType.CATEGORICAL:
            values = canonical_integer_strings(stripped) if integer_coded else stripped
        case LogicalType.UNKNOWN:
            values = stripped

    if logical_type in (LogicalType.CATEGORICAL, LogicalType.UNKNOWN):
        invalid = np.zeros(len(text), dtype=bool)
    else:
        invalid = present & values.isna().to_numpy(dtype=bool)
    values = values.mask(missing | invalid)
    return ParsedColumn(values=values, missing=missing, invalid=invalid)
