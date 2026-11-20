"""Drift injection.

Each function returns a modified copy and never mutates its input, so a
reference frame can be reused to derive several drifted variants.
"""

import numpy as np
import pandas as pd

from driftwatch_analytics.synthetic.specs import validate_category_distribution


def shift_mean(df: pd.DataFrame, column: str, delta: float) -> pd.DataFrame:
    """Add ``delta`` to every non-missing value of a numeric column (location shift)."""
    _require_numeric(df, column)
    _require_finite(delta, "delta")
    out = df.copy()
    out[column] = out[column] + delta
    return out


def scale_std(df: pd.DataFrame, column: str, factor: float) -> pd.DataFrame:
    """Multiply the spread of a numeric column by ``factor``, keeping its mean.

    ``x' = m + factor * (x - m)`` where ``m`` is the mean of non-missing values.
    The standard deviation scales by exactly ``factor``; the mean is unchanged.
    """
    _require_numeric(df, column)
    _require_finite(factor, "factor")
    if factor <= 0:
        raise ValueError(f"factor must be > 0, got {factor}")
    values = df[column]
    if values.notna().sum() == 0:
        raise ValueError(f"column {column!r} has no non-missing values to scale")
    mean = float(values.mean())
    out = df.copy()
    out[column] = mean + factor * (values - mean)
    return out


def resample_categories(
    df: pd.DataFrame,
    column: str,
    categories: tuple[str, ...],
    probabilities: tuple[float, ...],
    rng: np.random.Generator,
) -> pd.DataFrame:
    """Redraw the non-missing values of a column from a new category distribution.

    Categories absent from the original column are allowed; they simulate
    *unseen categories* in production. Missing positions stay missing so that
    category drift and missing-value drift can be tested separately.
    """
    _require_column(df, column)
    validate_category_distribution(column, categories, probabilities)
    present = df[column].notna().to_numpy()
    new_values = rng.choice(
        np.array(categories, dtype=object), size=int(present.sum()), p=probabilities
    )
    out = df.copy()
    column_values = out[column].astype("str").to_numpy(dtype=object, na_value=np.nan)
    column_values[present] = new_values
    out[column] = pd.Series(column_values, index=out.index, dtype="str")
    return out


def inject_missing(
    df: pd.DataFrame, column: str, rate: float, rng: np.random.Generator
) -> pd.DataFrame:
    """Set ``round(rate * len(df))`` randomly chosen rows of ``column`` to missing.

    Rows are chosen uniformly without replacement among all rows, so if the
    column already has missing values the final missing rate can exceed
    ``rate``. Other columns are not touched.
    """
    _require_column(df, column)
    _require_finite(rate, "rate")
    if not 0.0 <= rate <= 1.0:
        raise ValueError(f"rate must be in [0, 1], got {rate}")
    n_missing = round(rate * len(df))
    positions = rng.choice(len(df), size=n_missing, replace=False)
    mask = np.zeros(len(df), dtype=bool)
    mask[positions] = True
    out = df.copy()
    out[column] = out[column].mask(mask)
    return out


def _require_column(df: pd.DataFrame, column: str) -> None:
    if column not in df.columns:
        raise KeyError(f"column {column!r} not found; available: {list(df.columns)}")


def _require_numeric(df: pd.DataFrame, column: str) -> None:
    _require_column(df, column)
    dtype = df[column].dtype
    # Booleans count as numeric in pandas, but shifting them is meaningless.
    if not pd.api.types.is_numeric_dtype(dtype) or pd.api.types.is_bool_dtype(dtype):
        raise TypeError(f"column {column!r} must be numeric, got dtype {dtype}")


def _require_finite(value: float, name: str) -> None:
    if not np.isfinite(value):
        raise ValueError(f"{name} must be finite, got {value}")
