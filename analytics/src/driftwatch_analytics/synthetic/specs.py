"""Feature specifications for synthetic data.

Specs validate themselves on construction so an invalid scenario fails at the
point where it is defined, not deep inside a test.
"""

import math
from dataclasses import dataclass

# Probabilities are user-supplied floats; allow for rounding in literals such
# as (0.1, 0.2, 0.7) without accepting genuinely wrong distributions.
_PROBABILITY_SUM_TOLERANCE = 1e-9


def _require_name(name: str) -> None:
    if not name.strip():
        raise ValueError("feature name must be a non-empty string")


@dataclass(frozen=True)
class NormalFeature:
    """Numeric feature drawn from Normal(mean, std)."""

    name: str
    mean: float
    std: float

    def __post_init__(self) -> None:
        _require_name(self.name)
        if not math.isfinite(self.mean):
            raise ValueError(f"{self.name}: mean must be finite, got {self.mean}")
        if not (math.isfinite(self.std) and self.std > 0):
            raise ValueError(f"{self.name}: std must be finite and > 0, got {self.std}")


@dataclass(frozen=True)
class LogNormalFeature:
    """Right-skewed positive numeric feature (e.g. income).

    ``log(X) ~ Normal(mean_log, sigma)``. Skewed features matter because
    quantile-based binning (PSI) and moment-based checks behave differently on
    them than on symmetric data.
    """

    name: str
    mean_log: float
    sigma: float

    def __post_init__(self) -> None:
        _require_name(self.name)
        if not math.isfinite(self.mean_log):
            raise ValueError(f"{self.name}: mean_log must be finite, got {self.mean_log}")
        if not (math.isfinite(self.sigma) and self.sigma > 0):
            raise ValueError(f"{self.name}: sigma must be finite and > 0, got {self.sigma}")


@dataclass(frozen=True)
class CategoricalFeature:
    """String-valued feature with given category probabilities."""

    name: str
    categories: tuple[str, ...]
    probabilities: tuple[float, ...]

    def __post_init__(self) -> None:
        _require_name(self.name)
        validate_category_distribution(self.name, self.categories, self.probabilities)


def validate_category_distribution(
    name: str, categories: tuple[str, ...], probabilities: tuple[float, ...]
) -> None:
    """Raise ``ValueError`` unless ``probabilities`` is a valid distribution over ``categories``."""
    if not categories:
        raise ValueError(f"{name}: at least one category is required")
    if len(categories) != len(probabilities):
        raise ValueError(
            f"{name}: {len(categories)} categories but {len(probabilities)} probabilities"
        )
    if len(set(categories)) != len(categories):
        raise ValueError(f"{name}: categories must be unique")
    if any(not (math.isfinite(p) and p >= 0) for p in probabilities):
        raise ValueError(f"{name}: probabilities must be finite and >= 0")
    total = math.fsum(probabilities)
    if abs(total - 1.0) > _PROBABILITY_SUM_TOLERANCE:
        raise ValueError(f"{name}: probabilities must sum to 1, got {total}")


FeatureSpec = NormalFeature | LogNormalFeature | CategoricalFeature
