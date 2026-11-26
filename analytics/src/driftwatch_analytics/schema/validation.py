"""Validate a batch (reference or current) against a dataset schema.

Structural problems (a monitored column is missing) make the batch invalid.
Value-level problems never reject the batch; they are reported per column
so that drift results for healthy columns are still produced
(ML_MONITORING_DESIGN.md section 7).
"""

from dataclasses import dataclass, field
from enum import StrEnum

import pandas as pd

from driftwatch_analytics.schema.parsing import ParsedColumn, parse_column
from driftwatch_analytics.schema.types import ColumnRole, DatasetSchema, LogicalType


class Severity(StrEnum):
    ERROR = "error"
    WARNING = "warning"


class IssueCode(StrEnum):
    MISSING_COLUMN = "missing_column"
    MISSING_EXCLUDED_COLUMN = "missing_excluded_column"
    EXTRA_COLUMN = "extra_column"
    SCHEMA_VIOLATION = "schema_violation"
    EMPTY_BATCH = "empty_batch"


_SEVERITY: dict[IssueCode, Severity] = {
    IssueCode.MISSING_COLUMN: Severity.ERROR,
    IssueCode.MISSING_EXCLUDED_COLUMN: Severity.WARNING,
    IssueCode.EXTRA_COLUMN: Severity.WARNING,
    IssueCode.SCHEMA_VIOLATION: Severity.WARNING,
    IssueCode.EMPTY_BATCH: Severity.WARNING,
}


@dataclass(frozen=True)
class ValidationIssue:
    code: IssueCode
    message: str
    column: str | None = None

    @property
    def severity(self) -> Severity:
        return _SEVERITY[self.code]


@dataclass(frozen=True)
class ValidationConfig:
    # Share of non-missing values that may fail to parse before the column is
    # flagged as a schema violation.
    max_invalid_rate: float = 0.05

    def __post_init__(self) -> None:
        if not 0.0 <= self.max_invalid_rate <= 1.0:
            raise ValueError(f"max_invalid_rate must be in [0, 1], got {self.max_invalid_rate}")


@dataclass(frozen=True)
class ColumnValidation:
    name: str
    logical_type: LogicalType
    n_rows: int
    n_missing: int
    n_invalid: int
    schema_violation: bool

    @property
    def missing_rate(self) -> float | None:
        """Missing / all rows; ``None`` for an empty batch."""
        return self.n_missing / self.n_rows if self.n_rows else None

    @property
    def invalid_rate(self) -> float | None:
        """Invalid / non-missing rows; ``None`` when every value is missing."""
        present = self.n_rows - self.n_missing
        return self.n_invalid / present if present else None


@dataclass(frozen=True)
class BatchValidationReport:
    n_rows: int
    columns: tuple[ColumnValidation, ...]
    issues: tuple[ValidationIssue, ...]
    # Parsed values of monitored columns, so later stages do not parse twice.
    parsed: dict[str, ParsedColumn] = field(compare=False, repr=False)

    @property
    def is_valid(self) -> bool:
        return not any(issue.severity is Severity.ERROR for issue in self.issues)

    def column(self, name: str) -> ColumnValidation:
        for column in self.columns:
            if column.name == name:
                return column
        raise KeyError(name)


def validate_batch(
    data: pd.DataFrame, schema: DatasetSchema, config: ValidationConfig | None = None
) -> BatchValidationReport:
    """Check a text DataFrame (from ``read_csv_text``) against ``schema``.

    - Monitored schema column absent       -> ERROR, batch invalid.
    - Excluded schema column absent        -> WARNING (it is not needed).
    - Column not in schema                 -> WARNING, ignored.
    - Invalid-value rate > max_invalid_rate -> WARNING + column flagged.
    - Zero rows                            -> WARNING; drift stages decide what
      to do with too little data.
    """
    config = config or ValidationConfig()
    present = set(data.columns)
    issues: list[ValidationIssue] = []

    for schema_column in schema.columns:
        if schema_column.name in present:
            continue
        if schema_column.role is ColumnRole.TARGET:
            # Expected: labels usually arrive after the inputs (§6).
            continue
        if schema_column.monitored:
            issues.append(
                ValidationIssue(
                    IssueCode.MISSING_COLUMN,
                    f"required column {schema_column.name!r} is missing",
                    schema_column.name,
                )
            )
        else:
            issues.append(
                ValidationIssue(
                    IssueCode.MISSING_EXCLUDED_COLUMN,
                    (
                        f"record id column {schema_column.name!r} is missing; labels "
                        "cannot be joined to this batch"
                        if schema_column.role is ColumnRole.RECORD_ID
                        else f"excluded column {schema_column.name!r} is missing (not needed)"
                    ),
                    schema_column.name,
                )
            )

    known = set(schema.names)
    for name in data.columns:
        if name not in known:
            issues.append(
                ValidationIssue(
                    IssueCode.EXTRA_COLUMN, f"column {name!r} is not in the schema; ignored", name
                )
            )

    n_rows = len(data)
    if n_rows == 0:
        issues.append(ValidationIssue(IssueCode.EMPTY_BATCH, "batch contains no rows"))

    columns: list[ColumnValidation] = []
    parsed: dict[str, ParsedColumn] = {}
    for schema_column in schema.columns:
        if not schema_column.monitored or schema_column.name not in present:
            continue
        result = parse_column(
            data[schema_column.name],
            schema_column.logical_type,
            integer_coded=schema_column.integer_coded,
        )
        n_present = n_rows - result.n_missing
        violation = n_present > 0 and result.n_invalid / n_present > config.max_invalid_rate
        if violation:
            issues.append(
                ValidationIssue(
                    IssueCode.SCHEMA_VIOLATION,
                    f"{result.n_invalid} of {n_present} non-missing values in "
                    f"{schema_column.name!r} are not valid {schema_column.logical_type}",
                    schema_column.name,
                )
            )
        parsed[schema_column.name] = result
        columns.append(
            ColumnValidation(
                name=schema_column.name,
                logical_type=schema_column.logical_type,
                n_rows=n_rows,
                n_missing=result.n_missing,
                n_invalid=result.n_invalid,
                schema_violation=violation,
            )
        )

    return BatchValidationReport(
        n_rows=n_rows, columns=tuple(columns), issues=tuple(issues), parsed=parsed
    )
