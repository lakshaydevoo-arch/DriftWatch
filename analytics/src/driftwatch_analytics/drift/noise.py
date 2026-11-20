"""Sample-size-aware noise floor for histogram distances (PSI, Jensen-Shannon).

Why: both statistics are biased upward on small samples. With a 5,000-row
reference and 50 current rows, the fixed PSI threshold of 0.2 flagged 42% of
batches that had no drift at all (ML_MONITORING_DESIGN.md section 3.4).

How: simulate the statistic under the null hypothesis "current comes from
the reference distribution". Reference and current histograms are both
drawn as multinomials from the reference proportions with the real sample
sizes, so both sources of sampling noise are included. The ``quantile`` of
the simulated values is the noise floor; drift is flagged only above
``max(threshold, noise_floor)``. For large samples the floor falls far below
the fixed threshold and changes nothing.

The simulation is seeded from the config and the sample sizes, so the same
inputs always give the same floor.
"""

from collections.abc import Callable

import numpy as np
import numpy.typing as npt

from driftwatch_analytics.drift.models import NoiseFloorConfig

RowStatistic = Callable[[npt.NDArray[np.float64], npt.NDArray[np.float64]], npt.NDArray[np.float64]]


def noise_floor(
    statistic: RowStatistic,
    reference_proportions: npt.NDArray[np.float64],
    reference_count: int,
    current_count: int,
    config: NoiseFloorConfig,
) -> float:
    """``config.quantile`` of ``statistic`` under no drift.

    ``statistic`` takes two ``(n_simulations, n_buckets)`` arrays of
    proportions and returns one value per row.
    """
    if reference_count < 1 or current_count < 1:
        raise ValueError("sample sizes must be >= 1")
    p = np.asarray(reference_proportions, dtype=np.float64)
    p = p / p.sum()
    rng = np.random.default_rng([config.seed, reference_count, current_count, p.size])
    size = config.n_simulations
    reference = rng.multinomial(reference_count, p, size=size) / reference_count
    current = rng.multinomial(current_count, p, size=size) / current_count
    values = statistic(reference, current)
    return float(np.quantile(values, config.quantile, method="linear"))
