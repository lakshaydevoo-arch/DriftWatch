"""Schema data types."""

from dataclasses import dataclass
from enum import StrEnum


class LogicalType(StrEnum):
    NUMERIC = "numeric"
    CATEGORICAL = "categorical"
    BOOLEAN = "boolean"
    DATETIME = "datetime"
    # No non-missing values in the reference, so no type can be inferred.
    UNKNOWN = "unknown"


class ExclusionReason(StrEnum):
    """Why a column is present in the schema but not monitored for drift."""

    ALL_MISSING = "all_missing"
    DATETIME_NOT_SUPPORTED = "datetime_not_supported"
    LIKELY_IDENTIFIER = "likely_identifier"
    USER_EXCLUDED = "user_excluded"
    # Ground-truth labels usually arrive later than inputs, so the target is
    # not part of a batch's drift check (it is used for performance, §6).
    TARGET_COLUMN = "target_column"
    # Joins labels to predictions (§6); never a drift signal.
    RECORD_ID = "record_id"


class TypeSource(StrEnum):
    INFERRED = "inferred"
    OVERRIDE = "override"
    # Derived from the model's task type (prediction column, see inference.py).
    TASK_TYPE = "task_type"


class ColumnRole(StrEnum):
    """What a column is to the monitored model."""

    FEATURE = "feature"
    PREDICTION = "prediction"
    TARGET = "target"
    RECORD_ID = "record_id"


class TaskType(StrEnum):
    BINARY_CLASSIFICATION = "binary_classification"
    MULTICLASS_CLASSIFICATION = "multiclass_classification"
    REGRESSION = "regression"


@dataclass(frozen=True)
class ColumnSchema:
    name: str
    logical_type: LogicalType
    source: TypeSource
    excluded: ExclusionReason | None = None
    # Categorical column whose values are integers (e.g. a 1-5 rating).
    # Values are normalised so that "3" and "3.0" are the same category.
    integer_coded: bool = False
    # Defaults to FEATURE, so profiles stored before roles existed still load.
    role: ColumnRole = ColumnRole.FEATURE

    @property
    def monitored(self) -> bool:
        return self.excluded is None


@dataclass(frozen=True)
class DatasetSchema:
    columns: tuple[ColumnSchema, ...]
    # The model's task, when given; decides which performance metrics apply.
    # None for profiles built without one (or before it was recorded).
    task_type: TaskType | None = None

    def __post_init__(self) -> None:
        names = [column.name for column in self.columns]
        if len(set(names)) != len(names):
            raise ValueError("column names in a schema must be unique")

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(column.name for column in self.columns)

    def with_role(self, role: ColumnRole) -> tuple[ColumnSchema, ...]:
        return tuple(column for column in self.columns if column.role is role)

    def column(self, name: str) -> ColumnSchema:
        for column in self.columns:
            if column.name == name:
                return column
        raise KeyError(name)
