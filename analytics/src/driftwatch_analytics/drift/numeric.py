"""Numeric drift: Population Stability Index (primary) and two-sample KS.

See ML_MONITORING_DESIGN.md section 3 for the definitions, assumptions and
limitations.
"""

import numpy as np
import numpy.typing as npt
from scipy.stats import ks_2samp

from driftwatch_analytics.drift.models import NumericDriftConfig, NumericDriftResult
from driftwatch_analytics.profiling.binning import bin_counts
from driftwatch_analytics.profiling.models import NumericProfile


def population_stability_index(
    reference_proportions: npt.NDArray[np.float64],
    current_proportions: npt.NDArray[np.float64],
    epsilon: float,
) -> float:
    """``PSI = sum((c - r) * ln(c / r))`` over bins.

    Zero proportions on either side are replaced by ``epsilon`` (without
    renormalising) so the logarithm is finite. Each term is non-negative, so
    PSI >= 0, and it is symmetric in reference and current.
    """
    if reference_proportions.shape != current_proportions.shape:
        raise ValueError("reference and current must have the same number of bins")
    r = np.where(reference_proportions == 0, epsilon, reference_proportions)
    c = np.where(current_proportions == 0, epsilon, current_proportions)
    return float(np.sum((c - r) * np.log(c / r)))


def numeric_drift(
    reference: NumericProfile,
    current: npt.ArrayLike,
    config: NumericDriftConfig | None = None,
) -> NumericDriftResult:
    """Compare valid current values of one feature against its reference profile.

    ``current`` must contain only valid values: the caller removes missing
    and invalid ones (they are reported separately by validation).

    Raises:
        ValueError: if ``current`` is empty or contains non-finite values.
    """
    config = config or NumericDriftConfig()
    values = np.asarray(current, dtype=np.float64)
    if values.ndim != 1:
        raise ValueError("current values must be one-dimensional")
    if values.size == 0:
        raise ValueError("current values are empty; drift is undefined")
    if not np.isfinite(values).all():
        raise ValueError("current values must be finite; remove missing/invalid values first")

    edges = np.asarray(reference.bin_edges, dtype=np.float64)
    reference_proportions = np.asarray(reference.bin_counts, dtype=np.float64) / reference.count
    current_proportions = bin_counts(values, edges) / values.size
    psi = population_stability_index(reference_proportions, current_proportions, config.psi_epsilon)

    sample = np.asarray(reference.sample, dtype=np.float64)
    ks = ks_2samp(sample, values, alternative="two-sided", method="auto")

    return NumericDriftResult(
        psi=psi,
        threshold=config.psi_threshold,
        drift_detected=psi > config.psi_threshold,
        ks_statistic=float(ks.statistic),
        ks_p_value=float(ks.pvalue),
        reference_count=reference.count,
        reference_sample_size=sample.size,
        current_count=int(values.size),
        n_bins=edges.size + 1,
        reference_proportions=tuple(float(p) for p in reference_proportions),
        current_proportions=tuple(float(p) for p in current_proportions),
    )
