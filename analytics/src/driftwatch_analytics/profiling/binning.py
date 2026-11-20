"""Quantile binning shared by profiling and drift detection.

Profiles and drift runs must assign values to bins with exactly the same rule,
otherwise PSI compares mismatched histograms. Both use this module.

Convention: given strictly increasing interior edges ``e_1 < ... < e_k`` there
are ``k + 1`` right-closed bins::

    (-inf, e_1], (e_1, e_2], ..., (e_k, +inf)

The open outer bins mean current values outside the reference range are still
counted (ML_MONITORING_DESIGN.md section 3.1).
"""

import numpy as np
import numpy.typing as npt


def quantile_edges(values: npt.NDArray[np.float64], n_bins: int) -> npt.NDArray[np.float64]:
    """Interior bin edges at the reference quantiles ``1/n, ..., (n-1)/n``.

    Duplicate edges (heavily tied or discrete data) are merged, so fewer than
    ``n_bins`` bins can result; a constant column gives a single edge.
    """
    if n_bins < 2:
        raise ValueError(f"n_bins must be >= 2, got {n_bins}")
    if values.size == 0:
        raise ValueError("cannot compute quantile edges of an empty array")
    if not np.isfinite(values).all():
        raise ValueError("values must be finite")
    probabilities = np.arange(1, n_bins) / n_bins
    edges = np.quantile(values, probabilities, method="linear")
    return np.unique(edges)


def assign_bins(
    values: npt.NDArray[np.float64], edges: npt.NDArray[np.float64]
) -> npt.NDArray[np.intp]:
    """Bin index in ``[0, len(edges)]`` for each value (right-closed bins)."""
    return np.searchsorted(edges, values, side="left")


def bin_counts(
    values: npt.NDArray[np.float64], edges: npt.NDArray[np.float64]
) -> npt.NDArray[np.int64]:
    """Number of values in each of the ``len(edges) + 1`` bins."""
    return np.bincount(assign_bins(values, edges), minlength=edges.size + 1).astype(np.int64)
