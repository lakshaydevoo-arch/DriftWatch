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

Column roles (ML_MONITORING_DESIGN.md section 5):

- The *prediction* column is always monitored (never excluded by a
  heuristic). Unless overridden, its type follows the task: regression ->
  NUMERIC; classification -> NUMERIC if it holds probabilities (every value
  numeric in [0, 1], at least one not a whole number), otherwise the
  inferred label type (CATEGORICAL, or BOOLEAN for true/false labels).
- The *target* column keeps its inferred type but is excluded from drift
  (TARGET_COLUMN): labels usually arrive later than inputs.
- The *record id* column keeps its inferred type but is excluded
  (RECORD_ID): it only joins late labels to predictions.
"""

from collections.abc import Collection, Mapping
from dataclasses import dataclass, replace

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
    ColumnRole,
    ColumnSchema,
    DatasetSchema,
    ExclusionReason,
    LogicalType,
    TaskType,
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
    task_type: TaskType | None = None,
    prediction_column: str | None = None,
    target_column: str | None = None,
    record_id_column: str | None = None,
    config: InferenceConfig | None = None,
) -> DatasetSchema:
    """Infer the schema of a text DataFrame (as returned by ``read_csv_text``).

    Args:
        overrides: force a logical type for named columns. An overridden
            column is monitored unless also listed in ``exclude``.
        exclude: columns kept in the schema but not monitored.
        task_type: the model's task; required with ``prediction_column``.
        prediction_column: the model's output, monitored as prediction drift.
        target_column: the ground-truth label, kept but not monitored.
        record_id_column: joins labels to predictions, kept but not monitored.

    Raises:
        ValueError: if the frame has no rows, or the arguments name columns
            that do not exist or contradict each other.
    """
    config = config or InferenceConfig()
    overrides = dict(overrides or {})
    if len(data) == 0:
        raise ValueError("cannot infer a schema from a dataset with no rows")
    named = set(overrides) | set(exclude)
    named |= {c for c in (prediction_column, target_column, record_id_column) if c is not None}
    unknown = sorted(named - set(data.columns))
    if unknown:
        raise ValueError(f"options refer to columns not in the dataset: {unknown}")
    if LogicalType.UNKNOWN in overrides.values():
        raise ValueError("UNKNOWN cannot be used as an override; use exclude instead")
    _check_roles(exclude, task_type, prediction_column, target_column, record_id_column)

    columns = []
    for name in data.columns:
        if name == prediction_column:
            assert task_type is not None  # checked by _check_roles
            columns.append(_prediction(name, data[name], task_type, overrides.get(name), config))
            continue
        if name in overrides:
            column = ColumnSchema(
                name,
                overrides[name],
                TypeSource.OVERRIDE,
                integer_coded=_is_integer_coded(data[name], overrides[name]),
            )
        else:
            column = _infer_column(name, data[name], config)
        if name == target_column:
            column = replace(column, excluded=ExclusionReason.TARGET_COLUMN, role=ColumnRole.TARGET)
        elif name == record_id_column:
            column = replace(column, excluded=ExclusionReason.RECORD_ID, role=ColumnRole.RECORD_ID)
        elif name in exclude:
            column = replace(column, excluded=ExclusionReason.USER_EXCLUDED)
        columns.append(column)
    return DatasetSchema(tuple(columns))


def _check_roles(
    exclude: Collection[str],
    task_type: TaskType | None,
    prediction_column: str | None,
    target_column: str | None,
    record_id_column: str | None,
) -> None:
    if prediction_column is not None and task_type is None:
        raise ValueError("prediction_column requires task_type")
    roles = {
        "prediction_column": prediction_column,
        "target_column": target_column,
        "record_id_column": record_id_column,
    }
    given = {role: column for role, column in roles.items() if column is not None}
    if len(set(given.values())) != len(given):
        raise ValueError(f"{', '.join(given)} must be different columns")
    for role, column in given.items():
        if column in exclude:
            raise ValueError(f"{role} {column!r} cannot also be excluded")


def _prediction(
    name: str,
    text: pd.Series,
    task_type: TaskType,
    override: LogicalType | None,
    config: InferenceConfig,
) -> ColumnSchema:
    """The prediction column: always monitored, typed by override or by task."""
    role = ColumnRole.PREDICTION
    if override is not None:
        return ColumnSchema(
            name,
            override,
            TypeSource.OVERRIDE,
            integer_coded=_is_integer_coded(text, override),
            role=role,
        )
    if task_type is TaskType.REGRESSION or _looks_like_probabilities(text):
        return ColumnSchema(name, LogicalType.NUMERIC, TypeSource.TASK_TYPE, role=role)
    # Class labels. A boolean column (true/false labels) stays boolean;
    # anything else, including numbers such as 0/1 or 1-12, is categorical.
    inferred = _infer_column(name, text, config)
    if inferred.logical_type is LogicalType.BOOLEAN:
        return replace(inferred, source=TypeSource.TASK_TYPE, role=role)
    return ColumnSchema(
        name,
        LogicalType.CATEGORICAL,
        TypeSource.TASK_TYPE,
        integer_coded=_is_integer_coded(text, LogicalType.CATEGORICAL),
        role=role,
    )


def _looks_like_probabilities(text: pd.Series) -> bool:
    """Every non-missing value numeric in [0, 1], and at least one not whole."""
    stripped, missing = normalise(text)
    values = stripped[~missing]
    if values.empty:
        return False
    numbers = parse_numeric(values)
    if numbers.isna().any():
        return False
    return bool(
        (numbers >= 0).all() and (numbers <= 1).all() and not is_integer_valued(numbers).all()
    )


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
