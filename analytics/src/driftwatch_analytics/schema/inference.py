"""Infer a dataset schema from a reference dataset.

Rules (ML_MONITORING_DESIGN.md section 7), applied to non-missing values in
this order:

1. no non-missing values               -> UNKNOWN, excluded (ALL_MISSING)
2. all values are boolean tokens       -> BOOLEAN
3. >= ``numeric_ratio`` parse as numbers:
   - integer-valued with <= ``max_integer_categories`` distinct values
                                       -> CATEGORICAL (integer_coded)
   - otherwise                         -> NUMERIC
4. >= ``datetime_ratio`` parse as ISO 8601 dates -> DATETIME, excluded
5. otherwise                           -> CATEGORICAL; excluded as
   LIKELY_IDENTIFIER when almost every value is distinct

These are heuristics. Users can correct them with ``overrides`` and ``exclude``.
"""

from collections.abc import Collection, Mapping
from dataclasses import dataclass

import pandas as pd

from driftwatch_analytics.schema.parsing import (
    FALSE_TOKENS,
    TRUE_TOKENS,
    is_integer_valued,
    normalise,
    parse_datetime,
    parse_numeric,
)
from driftwatch_analytics.schema.types import (
    ColumnSchema,
    DatasetSchema,
    ExclusionReason,
    LogicalType,
    TypeSource,
)


@dataclass(frozen=True)
class InferenceConfig:
    numeric_ratio: float = 0.95
    datetime_ratio: float = 0.95
    max_integer_categories: int = 10
    identifier_distinct_ratio: float = 0.9
    # Below this many values, "almost all distinct" is expected by chance and
    # says nothing about whether the column is an identifier.
    identifier_min_count: int = 50

    def __post_init__(self) -> None:
        for name in ("numeric_ratio", "datetime_ratio", "identifier_distinct_ratio"):
            value = getattr(self, name)
            if not 0.0 < value <= 1.0:
                raise ValueError(f"{name} must be in (0, 1], got {value}")
        if self.max_integer_categories < 0:
            raise ValueError("max_integer_categories must be >= 0")
        if self.identifier_min_count < 1:
            raise ValueError("identifier_min_count must be >= 1")


def infer_schema(
    data: pd.DataFrame,
    *,
    overrides: Mapping[str, LogicalType] | None = None,
    exclude: Collection[str] = (),
    config: InferenceConfig | None = None,
) -> DatasetSchema:
    """Infer the schema of a text DataFrame (as returned by ``read_csv_text``).

    Args:
        overrides: force a logical type for named columns. An overridden
            column is monitored unless also listed in ``exclude``.
        exclude: columns kept in the schema but not monitored.

    Raises:
        ValueError: if the frame has no rows, or ``overrides``/``exclude``
            name columns that do not exist.
    """
    config = config or InferenceConfig()
    overrides = dict(overrides or {})
    if len(data) == 0:
        raise ValueError("cannot infer a schema from a dataset with no rows")
    unknown = sorted((set(overrides) | set(exclude)) - set(data.columns))
    if unknown:
        raise ValueError(f"overrides/exclude refer to unknown columns: {unknown}")
    if LogicalType.UNKNOWN in overrides.values():
        raise ValueError("UNKNOWN cannot be used as an override; use exclude instead")

    columns = []
    for name in data.columns:
        if name in overrides:
            column = ColumnSchema(
                name,
                overrides[name],
                TypeSource.OVERRIDE,
                integer_coded=_is_integer_coded(data[name], overrides[name]),
            )
        else:
            column = _infer_column(name, data[name], config)
        if name in exclude:
            column = ColumnSchema(
                column.name,
                column.logical_type,
                column.source,
                ExclusionReason.USER_EXCLUDED,
                column.integer_coded,
            )
        columns.append(column)
    return DatasetSchema(tuple(columns))


def _infer_column(name: str, text: pd.Series, config: InferenceConfig) -> ColumnSchema:
    stripped, missing = normalise(text)
    values = stripped[~missing]
    inferred = TypeSource.INFERRED

    if values.empty:
        return ColumnSchema(name, LogicalType.UNKNOWN, inferred, ExclusionReason.ALL_MISSING)

    if values.str.lower().isin(TRUE_TOKENS | FALSE_TOKENS).all():
        return ColumnSchema(name, LogicalType.BOOLEAN, inferred)

    numbers = parse_numeric(values)
    if numbers.notna().mean() >= config.numeric_ratio:
        parsed = numbers.dropna()
        if (
            bool(is_integer_valued(parsed).all())
            and parsed.nunique() <= config.max_integer_categories
        ):
            return ColumnSchema(name, LogicalType.CATEGORICAL, inferred, integer_coded=True)
        return ColumnSchema(name, LogicalType.NUMERIC, inferred)

    if parse_datetime(values).notna().mean() >= config.datetime_ratio:
        return ColumnSchema(
            name, LogicalType.DATETIME, inferred, ExclusionReason.DATETIME_NOT_SUPPORTED
        )

    if (
        len(values) >= config.identifier_min_count
        and values.nunique() / len(values) > config.identifier_distinct_ratio
    ):
        return ColumnSchema(
            name, LogicalType.CATEGORICAL, inferred, ExclusionReason.LIKELY_IDENTIFIER
        )
    return ColumnSchema(name, LogicalType.CATEGORICAL, inferred)


def _is_integer_coded(text: pd.Series, logical_type: LogicalType) -> bool:
    """For a categorical override, normalise integers if every value is an integer."""
    if logical_type is not LogicalType.CATEGORICAL:
        return False
    stripped, missing = normalise(text)
    numbers = parse_numeric(stripped[~missing])
    return bool(len(numbers) > 0 and is_integer_valued(numbers).all())
