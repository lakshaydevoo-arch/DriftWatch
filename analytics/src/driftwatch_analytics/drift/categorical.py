"""Categorical drift: Jensen-Shannon distance (primary) and chi-square.

See ML_MONITORING_DESIGN.md section 4.
"""

from collections import Counter
from collections.abc import Iterable

import numpy as np
import numpy.typing as npt
from scipy.spatial.distance import jensenshannon
from scipy.stats import chi2_contingency

from driftwatch_analytics.drift.models import CategoricalDriftConfig, CategoricalDriftResult
from driftwatch_analytics.profiling.models import CategoricalProfile

# Bucket key for "categories not tracked individually".
_OTHER: None = None


def _buckets(
    reference: CategoricalProfile, current: Counter[str]
) -> tuple[list[str | None], list[int], list[int], list[str]]:
    """Align reference and current counts on a common, ordered set of buckets.

    Order: kept reference categories (sorted), then unseen current categories
    (sorted), then the "other" bucket if it holds any mass.

    If the profile is complete (no ``other_count``), every unseen current
    category gets its own bucket with reference probability 0. If the profile
    was truncated to its top categories, unseen current values are merged into
    the "other" bucket, because they may be rare reference categories and
    giving them their own bucket would overstate drift.
    """
    kept = sorted(reference.counts)
    unseen = sorted(set(current) - set(reference.counts))
    truncated = reference.other_count > 0

    labels: list[str | None] = list(kept)
    ref_counts = [reference.counts[c] for c in kept]
    cur_counts = [current.get(c, 0) for c in kept]
    if not truncated:
        labels += unseen
        ref_counts += [0] * len(unseen)
        cur_counts += [current[c] for c in unseen]
    other_current = sum(current[c] for c in unseen) if truncated else 0
    if reference.other_count or other_current:
        labels.append(_OTHER)
        ref_counts.append(reference.other_count)
        cur_counts.append(other_current)
    return labels, ref_counts, cur_counts, unseen


def pool_rare_buckets(counts: npt.NDArray[np.int64], min_expected: float) -> list[list[int]]:
    """Group the columns of a 2 x k table so every expected count is >= ``min_expected``.

    In a 2 x k table the expected count of cell (i, j) is ``R_i * C_j / N``,
    so column j is large enough iff ``C_j >= min_expected * N / min(R)``.
    Columns below that are merged into one pooled group; while the pool is
    still too small it absorbs the smallest remaining column. Returns groups
    of column indices (the pooled group, if any, last). Fewer than two groups
    means the test cannot be run.
    """
    totals = counts.sum(axis=0)
    rows = counts.sum(axis=1)
    required = min_expected * totals.sum() / rows.min()
    # Stable order: by total, then by position, so pooling is deterministic.
    order = sorted(range(totals.size), key=lambda j: (totals[j], j))
    pooled = [j for j in order if totals[j] < required]
    keep = [j for j in order if totals[j] >= required]
    while pooled and sum(totals[j] for j in pooled) < required and keep:
        pooled.append(keep.pop(0))
    groups = [[j] for j in sorted(keep)]
    if pooled:
        groups.append(sorted(pooled))
    return groups


def categorical_drift(
    reference: CategoricalProfile,
    current: Iterable[str],
    config: CategoricalDriftConfig | None = None,
) -> CategoricalDriftResult:
    """Compare valid current labels of one feature against its reference profile.

    ``current`` must contain only valid labels; convert parsed values with
    ``profiling.category_labels`` so that labels match the profile.

    Raises:
        ValueError: if ``current`` is empty or contains non-string values.
    """
    config = config or CategoricalDriftConfig()
    labels_in = list(current)
    if not labels_in:
        raise ValueError("current values are empty; drift is undefined")
    if not all(isinstance(v, str) for v in labels_in):
        raise ValueError("current values must be strings; use profiling.category_labels")
    counter = Counter(labels_in)

    labels, ref_list, cur_list, unseen = _buckets(reference, counter)
    ref_counts = np.array(ref_list, dtype=np.int64)
    cur_counts = np.array(cur_list, dtype=np.int64)
    ref_p = ref_counts / ref_counts.sum()
    cur_p = cur_counts / cur_counts.sum()

    js = float(jensenshannon(ref_p, cur_p, base=2))
    js = min(max(js, 0.0), 1.0)  # guard against floating-point excursions

    warnings: list[str] = []
    chi2_statistic: float | None = None
    chi2_p_value: float | None = None
    chi2_dof: int | None = None
    pooled_labels: tuple[str | None, ...] = ()

    table = np.vstack([ref_counts, cur_counts])
    groups = pool_rare_buckets(table, config.chi2_min_expected)
    if len(groups) < 2:
        warnings.append(
            "chi-square test skipped: too few observations for expected counts "
            f">= {config.chi2_min_expected:g} in at least two categories"
        )
    else:
        merged = np.column_stack([table[:, g].sum(axis=1) for g in groups])
        test = chi2_contingency(merged, correction=False)
        chi2_statistic = float(test.statistic)
        chi2_p_value = float(test.pvalue)
        chi2_dof = int(test.dof)
        if len(groups[-1]) > 1:
            pooled_labels = tuple(labels[j] for j in groups[-1])

    return CategoricalDriftResult(
        js_distance=js,
        threshold=config.js_threshold,
        drift_detected=js > config.js_threshold,
        chi2_statistic=chi2_statistic,
        chi2_p_value=chi2_p_value,
        chi2_dof=chi2_dof,
        chi2_pooled=pooled_labels,
        reference_count=reference.count,
        current_count=len(labels_in),
        categories=tuple(labels),
        reference_proportions=tuple(float(p) for p in ref_p),
        current_proportions=tuple(float(p) for p in cur_p),
        unseen_categories=tuple(unseen[: config.max_reported_unseen]),
        n_unseen_categories=len(unseen),
        reference_truncated=reference.other_count > 0,
        warnings=tuple(warnings),
    )
