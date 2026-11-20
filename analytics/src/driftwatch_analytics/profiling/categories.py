"""Category labels shared by profiling and drift detection.

Like ``binning``, this is the single place that decides how parsed values
become category labels, so the profile and drift runs cannot disagree
(e.g. a boolean ``True`` must be ``"true"`` on both sides).
"""

import pandas as pd

from driftwatch_analytics.schema.types import LogicalType

_BOOLEAN_LABELS = {True: "true", False: "false"}


def category_labels(values: pd.Series, logical_type: LogicalType) -> pd.Series:
    """Valid values of a categorical or boolean column as string labels.

    Missing values (already masked by ``parse_column``) are dropped.
    """
    if logical_type not in (LogicalType.CATEGORICAL, LogicalType.BOOLEAN):
        raise ValueError(f"expected a categorical or boolean column, got {logical_type}")
    present = values.dropna()
    if logical_type is LogicalType.BOOLEAN:
        present = present.map(_BOOLEAN_LABELS)
    return present.astype("str")
