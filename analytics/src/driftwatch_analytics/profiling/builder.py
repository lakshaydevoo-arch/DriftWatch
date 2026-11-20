"""Build a reference profile from a reference dataset."""

import platform
import zlib

import numpy as np
import numpy.typing as npt
import pandas as pd

from driftwatch_analytics._version import ENGINE_VERSION
from driftwatch_analytics.profiling.binning import bin_counts, quantile_edges
from driftwatch_analytics.profiling.categories import category_labels
from driftwatch_analytics.profiling.models import (
    CategoricalProfile,
    FeatureProfile,
    NumericProfile,
    ProfileConfig,
    ReferenceProfile,
)
from driftwatch_analytics.schema.parsing import ParsedColumn
from driftwatch_analytics.schema.types import DatasetSchema, LogicalType
from driftwatch_analytics.schema.validation import validate_batch


class ProfileError(ValueError):
    """The reference dataset cannot be profiled."""


def build_reference_profile(
    data: pd.DataFrame, schema: DatasetSchema, config: ProfileConfig | None = None
) -> ReferenceProfile:
    """Profile every monitored column of a text DataFrame (from ``read_csv_text``).

    Deterministic: the same data, schema, config and library versions always
    give an identical profile (ML_MONITORING_DESIGN.md section 8).

    Raises:
        ProfileError: if the data does not match the schema, has no rows, or a
            monitored column has no valid values.
    """
    config = config or ProfileConfig()
    report = validate_batch(data, schema)
    if not report.is_valid:
        problems = [issue.message for issue in report.issues if issue.column]
        raise ProfileError(f"reference data does not match its schema: {problems}")
    if report.n_rows == 0:
        raise ProfileError("reference dataset has no rows")

    empty = [name for name, parsed in report.parsed.items() if parsed.values.notna().sum() == 0]
    if empty:
        raise ProfileError(
            f"monitored columns without any valid values: {empty}; exclude them or fix the types"
        )

    features: dict[str, FeatureProfile] = {}
    for column in schema.columns:
        if not column.monitored:
            continue
        parsed = report.parsed[column.name]
        if column.logical_type is LogicalType.NUMERIC:
            features[column.name] = _numeric_profile(column.name, parsed, config)
        else:
            features[column.name] = _categorical_profile(parsed, column.logical_type, config)

    return ReferenceProfile(
        engine_version=ENGINE_VERSION,
        library_versions={
            "python": platform.python_version(),
            "numpy": np.__version__,
            "pandas": pd.__version__,
        },
        config=config,
        dataset_schema=schema,
        n_rows=report.n_rows,
        features=features,
        issues=report.issues,
    )


def _feature_rng(seed: int, name: str) -> np.random.Generator:
    """Independent, reproducible stream per feature.

    Seeding with ``(seed, crc32(name))`` means adding, removing or reordering
    other columns does not change this feature's sample. ``crc32`` is used
    instead of ``hash()`` because ``hash`` of a string changes between Python
    processes.
    """
    return np.random.default_rng([seed, zlib.crc32(name.encode("utf-8"))])


def _numeric_profile(name: str, parsed: ParsedColumn, config: ProfileConfig) -> NumericProfile:
    values: npt.NDArray[np.float64] = parsed.values.dropna().to_numpy(dtype=np.float64)
    edges = quantile_edges(values, config.n_bins)
    if values.size > config.sample_size:
        sample = _feature_rng(config.seed, name).choice(
            values, size=config.sample_size, replace=False
        )
    else:
        sample = values
    minimum, maximum = float(values.min()), float(values.max())
    # Clamp: floating-point summation can put the mean of a constant column a
    # rounding error outside [min, max].
    mean = min(max(float(values.mean()), minimum), maximum)
    return NumericProfile(
        n_missing=parsed.n_missing,
        n_invalid=parsed.n_invalid,
        count=int(values.size),
        mean=mean,
        std=float(values.std(ddof=0)),
        min=minimum,
        max=maximum,
        is_constant=minimum == maximum,
        bin_edges=tuple(float(e) for e in edges),
        bin_counts=tuple(int(c) for c in bin_counts(values, edges)),
        sample=tuple(float(v) for v in np.sort(sample)),
    )


def _categorical_profile(
    parsed: ParsedColumn, logical_type: LogicalType, config: ProfileConfig
) -> CategoricalProfile:
    values = category_labels(parsed.values, logical_type)
    frequencies = values.value_counts()
    # Top categories by count; ties broken by value so the choice is deterministic.
    ranked = sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))
    kept = dict(sorted(ranked[: config.max_categories]))
    other = sum(count for _, count in ranked[config.max_categories :])
    return CategoricalProfile(
        n_missing=parsed.n_missing,
        n_invalid=parsed.n_invalid,
        count=int(values.size),
        counts={str(k): int(v) for k, v in kept.items()},
        other_count=int(other),
        n_distinct=len(ranked),
    )
