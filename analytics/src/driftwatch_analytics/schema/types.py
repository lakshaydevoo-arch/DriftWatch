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


class TypeSource(StrEnum):
    INFERRED = "inferred"
    OVERRIDE = "override"


@dataclass(frozen=True)
class ColumnSchema:
    name: str
    logical_type: LogicalType
    source: TypeSource
    excluded: ExclusionReason | None = None
    # Categorical column whose values are integers (e.g. a 1-5 rating).
    # Values are normalised so that "3" and "3.0" are the same category.
    integer_coded: bool = False

    @property
    def monitored(self) -> bool:
        return self.excluded is None


@dataclass(frozen=True)
class DatasetSchema:
    columns: tuple[ColumnSchema, ...]

    def __post_init__(self) -> None:
        names = [column.name for column in self.columns]
        if len(set(names)) != len(names):
            raise ValueError("column names in a schema must be unique")

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(column.name for column in self.columns)

    def column(self, name: str) -> ColumnSchema:
        for column in self.columns:
            if column.name == name:
                return column
        raise KeyError(name)
