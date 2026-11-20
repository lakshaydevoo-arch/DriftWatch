"""Reference profile data model.

The profile is computed once per reference dataset, stored by the backend as
JSON and sent back with every drift request. It is therefore a Pydantic model:
it serialises to JSON and is *re-validated* when loaded, so a corrupted or
hand-edited profile fails loudly instead of producing wrong drift results.
"""

from typing import Annotated, Final, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from driftwatch_analytics.schema.types import DatasetSchema
from driftwatch_analytics.schema.validation import ValidationIssue

PROFILE_FORMAT_VERSION: Final = 1


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class ProfileConfig(_Frozen):
    n_bins: int = Field(default=10, ge=2, le=100)
    sample_size: int = Field(default=10_000, ge=1)
    max_categories: int = Field(default=100, ge=1)
    seed: int = Field(default=42, ge=0)


class NumericProfile(_Frozen):
    kind: Literal["numeric"] = "numeric"
    n_missing: int = Field(ge=0)
    n_invalid: int = Field(ge=0)
    count: int = Field(ge=1, description="Valid (non-missing, parseable) values")
    mean: float
    std: float = Field(ge=0, description="Population standard deviation (ddof=0)")
    min: float
    max: float
    is_constant: bool
    bin_edges: tuple[float, ...] = Field(description="Interior edges; bins are right-closed")
    bin_counts: tuple[int, ...]
    sample: tuple[float, ...] = Field(description="Seeded sample of valid values, sorted")

    @model_validator(mode="after")
    def _check_consistency(self) -> Self:
        if not self.bin_edges:
            raise ValueError("at least one bin edge is required")
        if any(a >= b for a, b in zip(self.bin_edges, self.bin_edges[1:], strict=False)):
            raise ValueError("bin_edges must be strictly increasing")
        if len(self.bin_counts) != len(self.bin_edges) + 1:
            raise ValueError("bin_counts must have len(bin_edges) + 1 entries")
        if any(c < 0 for c in self.bin_counts) or sum(self.bin_counts) != self.count:
            raise ValueError("bin_counts must be non-negative and sum to count")
        if not 1 <= len(self.sample) <= self.count:
            raise ValueError("sample size must be between 1 and count")
        if list(self.sample) != sorted(self.sample):
            raise ValueError("sample must be sorted")
        if not self.min <= self.mean <= self.max:
            raise ValueError("mean must lie between min and max")
        return self


class CategoricalProfile(_Frozen):
    kind: Literal["categorical"] = "categorical"
    n_missing: int = Field(ge=0)
    n_invalid: int = Field(ge=0)
    count: int = Field(ge=1, description="Valid (non-missing, parseable) values")
    counts: dict[str, int] = Field(description="Kept categories, sorted by value")
    other_count: int = Field(ge=0, description="Values of categories beyond max_categories")
    n_distinct: int = Field(ge=1, description="Distinct values before truncation")

    @model_validator(mode="after")
    def _check_consistency(self) -> Self:
        if not self.counts:
            raise ValueError("at least one category is required")
        if any(c < 1 for c in self.counts.values()):
            raise ValueError("category counts must be >= 1")
        if sum(self.counts.values()) + self.other_count != self.count:
            raise ValueError("category counts plus other_count must equal count")
        if self.n_distinct < len(self.counts):
            raise ValueError("n_distinct cannot be smaller than the number of kept categories")
        return self


FeatureProfile = Annotated[NumericProfile | CategoricalProfile, Field(discriminator="kind")]


class ReferenceProfile(_Frozen):
    format_version: Literal[1] = PROFILE_FORMAT_VERSION
    engine_version: str
    library_versions: dict[str, str]
    config: ProfileConfig
    dataset_schema: DatasetSchema
    n_rows: int = Field(ge=1)
    features: dict[str, FeatureProfile]
    issues: tuple[ValidationIssue, ...] = ()

    @model_validator(mode="after")
    def _check_features_match_schema(self) -> Self:
        monitored = {c.name for c in self.dataset_schema.columns if c.monitored}
        if set(self.features) != monitored:
            raise ValueError("features must be exactly the monitored schema columns")
        for name, feature in self.features.items():
            if feature.count + feature.n_missing + feature.n_invalid != self.n_rows:
                raise ValueError(f"{name}: count + n_missing + n_invalid must equal n_rows")
        return self
