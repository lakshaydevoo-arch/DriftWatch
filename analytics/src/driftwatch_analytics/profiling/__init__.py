"""Reference profiles: compact, deterministic summaries of reference data.

See ARCHITECTURE.md (decision D3) and ML_MONITORING_DESIGN.md section 8.
"""

from driftwatch_analytics.profiling.binning import assign_bins, bin_counts, quantile_edges
from driftwatch_analytics.profiling.builder import ProfileError, build_reference_profile
from driftwatch_analytics.profiling.models import (
    PROFILE_FORMAT_VERSION,
    CategoricalProfile,
    FeatureProfile,
    NumericProfile,
    ProfileConfig,
    ReferenceProfile,
)

__all__ = [
    "PROFILE_FORMAT_VERSION",
    "CategoricalProfile",
    "FeatureProfile",
    "NumericProfile",
    "ProfileConfig",
    "ProfileError",
    "ReferenceProfile",
    "assign_bins",
    "bin_counts",
    "build_reference_profile",
    "quantile_edges",
]
