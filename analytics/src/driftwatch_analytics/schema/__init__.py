"""Reading tabular data, inferring its schema and validating batches against it.

See ML_MONITORING_DESIGN.md section 7.
"""

from driftwatch_analytics.schema.csv_reader import CsvFormatError, read_csv_text
from driftwatch_analytics.schema.inference import InferenceConfig, infer_schema
from driftwatch_analytics.schema.parsing import MISSING_TOKENS, ParsedColumn, parse_column
from driftwatch_analytics.schema.types import (
    ColumnRole,
    ColumnSchema,
    DatasetSchema,
    ExclusionReason,
    LogicalType,
    TaskType,
    TypeSource,
)
from driftwatch_analytics.schema.validation import (
    BatchValidationReport,
    ColumnValidation,
    IssueCode,
    Severity,
    ValidationConfig,
    ValidationIssue,
    validate_batch,
)

__all__ = [
    "MISSING_TOKENS",
    "BatchValidationReport",
    "ColumnRole",
    "ColumnSchema",
    "ColumnValidation",
    "CsvFormatError",
    "DatasetSchema",
    "ExclusionReason",
    "InferenceConfig",
    "IssueCode",
    "LogicalType",
    "ParsedColumn",
    "Severity",
    "TaskType",
    "TypeSource",
    "ValidationConfig",
    "ValidationIssue",
    "infer_schema",
    "parse_column",
    "read_csv_text",
    "validate_batch",
]
