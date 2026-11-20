"""Numeric drift: PSI (primary), two-sample KS and normalised Wasserstein.

See ML_MONITORING_DESIGN.md section 3 for the definitions, assumptions and
limitations.
"""

import numpy as np
import numpy.typing as npt
from scipy.stats import ks_2samp, wasserstein_distance

from driftwatch_analytics.drift.models import NumericDriftConfig, NumericDriftResult
from driftwatch_analytics.drift.noise import noise_floor
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
    return float(_psi_rows(reference_proportions, current_proportions, epsilon))


def _psi_rows(
    reference: npt.NDArray[np.float64], current: npt.NDArray[np.float64], epsilon: float
) -> npt.NDArray[np.float64]:
    """PSI along the last axis (one value per row for 2-D input)."""
    r = np.where(reference == 0, epsilon, reference)
    c = np.where(current == 0, epsilon, current)
    result: npt.NDArray[np.float64] = np.sum((c - r) * np.log(c / r), axis=-1)
    return result


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
    floor = (
        noise_floor(
            lambda r, c: _psi_rows(r, c, config.psi_epsilon),
            reference_proportions,
            reference.count,
            int(values.size),
            config.noise_floor,
        )
        if config.noise_floor is not None
        else None
    )
    effective = max(config.psi_threshold, floor) if floor is not None else config.psi_threshold

    sample = np.asarray(reference.sample, dtype=np.float64)
    ks = ks_2samp(sample, values, alternative="two-sided", method="auto")
    wasserstein = (
        float(wasserstein_distance(sample, values)) / reference.std if reference.std > 0 else None
    )

    return NumericDriftResult(
        psi=psi,
        threshold=config.psi_threshold,
        noise_floor=floor,
        effective_threshold=effective,
        drift_detected=psi > effective,
        ks_statistic=float(ks.statistic),
        ks_p_value=float(ks.pvalue),
        wasserstein_normalized=wasserstein,
        reference_count=reference.count,
        reference_sample_size=sample.size,
        current_count=int(values.size),
        n_bins=edges.size + 1,
        reference_proportions=tuple(float(p) for p in reference_proportions),
        current_proportions=tuple(float(p) for p in current_proportions),
    )
