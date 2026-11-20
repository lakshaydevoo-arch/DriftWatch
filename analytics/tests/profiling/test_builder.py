import json
import subprocess
import sys

import numpy as np
import pandas as pd
import pytest
from pydantic import ValidationError

from driftwatch_analytics import ENGINE_VERSION
from driftwatch_analytics.profiling import (
    CategoricalProfile,
    NumericProfile,
    ProfileConfig,
    ProfileError,
    ReferenceProfile,
    build_reference_profile,
)
from driftwatch_analytics.schema import (
    DatasetSchema,
    IssueCode,
    LogicalType,
    infer_schema,
    read_csv_text,
)
from driftwatch_analytics.synthetic import LOAN_APPLICANT_FEATURES, generate_dataset, inject_missing

N_ROWS = 5_000


def _text(df: pd.DataFrame) -> pd.DataFrame:
    return read_csv_text(df.to_csv(index=False).encode())


@pytest.fixture
def reference() -> pd.DataFrame:
    df = generate_dataset(LOAN_APPLICANT_FEATURES, N_ROWS, np.random.default_rng(0))
    return inject_missing(df, "income", 0.1, np.random.default_rng(1))


def _build(df: pd.DataFrame, config: ProfileConfig | None = None) -> ReferenceProfile:
    text = _text(df)
    return build_reference_profile(text, infer_schema(text), config)


def _numeric(profile: ReferenceProfile, name: str) -> NumericProfile:
    feature = profile.features[name]
    assert isinstance(feature, NumericProfile)
    return feature


def _categorical(profile: ReferenceProfile, name: str) -> CategoricalProfile:
    feature = profile.features[name]
    assert isinstance(feature, CategoricalProfile)
    return feature


# --- contents ---------------------------------------------------------------


def test_profile_metadata(reference: pd.DataFrame) -> None:
    profile = _build(reference)

    assert profile.engine_version == ENGINE_VERSION
    assert set(profile.library_versions) == {"python", "numpy", "pandas"}
    assert profile.config == ProfileConfig()
    assert profile.n_rows == N_ROWS
    assert set(profile.features) == set(reference.columns)


def test_numeric_profile_matches_data(reference: pd.DataFrame) -> None:
    income = _numeric(_build(reference), "income")
    valid = reference["income"].dropna()

    assert income.n_missing == 500
    assert income.count == N_ROWS - 500
    assert income.mean == pytest.approx(valid.mean())
    assert income.std == pytest.approx(valid.std(ddof=0))
    assert (income.min, income.max) == pytest.approx((valid.min(), valid.max()))
    assert not income.is_constant
    # Deciles of continuous data: 10 bins with (nearly) equal counts.
    assert len(income.bin_counts) == 10
    assert max(income.bin_counts) - min(income.bin_counts) <= 1
    assert income.sample == tuple(sorted(income.sample))


def test_small_columns_keep_every_value_in_sample(reference: pd.DataFrame) -> None:
    age = _numeric(_build(reference), "age")

    assert len(age.sample) == N_ROWS
    np.testing.assert_allclose(age.sample, np.sort(reference["age"].to_numpy()))


def test_large_columns_are_sampled_to_the_cap(reference: pd.DataFrame) -> None:
    age = _numeric(_build(reference, ProfileConfig(sample_size=1_000)), "age")

    assert len(age.sample) == 1_000
    # Drawn without replacement from the actual values (CSV floats round-trip exactly).
    assert len(set(age.sample)) == 1_000
    assert np.isin(age.sample, reference["age"].to_numpy()).all()


def test_categorical_profile_counts(reference: pd.DataFrame) -> None:
    region = _categorical(_build(reference), "region")

    assert region.counts == dict(sorted(reference["region"].value_counts().items()))
    assert list(region.counts) == sorted(region.counts)
    assert region.other_count == 0
    assert region.n_distinct == 4


def test_high_cardinality_keeps_top_categories_and_counts_the_rest() -> None:
    # 30 categories with counts 30, 29, ..., 1 (465 rows): a, b, ... by frequency.
    values = [f"cat{i:02d}" for i in range(30) for _ in range(30 - i)]
    df = pd.DataFrame({"c": values})

    profile = _categorical(_build(df, ProfileConfig(max_categories=5)), "c")

    assert profile.counts == {"cat00": 30, "cat01": 29, "cat02": 28, "cat03": 27, "cat04": 26}
    assert profile.other_count == sum(range(1, 26))
    assert profile.n_distinct == 30


def test_ties_at_the_category_cutoff_are_broken_by_value() -> None:
    df = pd.DataFrame({"c": ["b", "a", "c", "b", "a", "c"]})

    profile = _categorical(_build(df, ProfileConfig(max_categories=2)), "c")

    assert profile.counts == {"a": 2, "b": 2}
    assert profile.other_count == 2


def test_boolean_columns_are_profiled_as_true_false_categories() -> None:
    # "maybe" stops inference from choosing boolean, so the type is set explicitly.
    text = pd.DataFrame({"flag": ["yes", "no", "YES", "1", "maybe"] * 4}, dtype="str")
    schema = infer_schema(text, overrides={"flag": LogicalType.BOOLEAN})

    profile = build_reference_profile(text, schema)

    flag = _categorical(profile, "flag")
    assert flag.counts == {"false": 4, "true": 12}
    assert flag.n_invalid == 4
    # 20% invalid is above the 5% tolerance, so the issue is carried into the profile.
    assert [i.code for i in profile.issues] == [IssueCode.SCHEMA_VIOLATION]


def test_integer_coded_categories_are_normalised() -> None:
    df = pd.DataFrame({"rating": ["1", "2", "2.0", "3", "", "3.0"]})

    rating = _categorical(_build(df), "rating")

    assert rating.counts == {"1": 1, "2": 2, "3": 2}
    assert rating.n_missing == 1


def test_constant_numeric_column() -> None:
    df = pd.DataFrame({"x": ["7.5"] * 40 + [""] * 10, "y": [str(v) for v in range(50)]})

    x = _numeric(_build(df), "x")

    assert x.is_constant
    assert x.std == 0.0
    assert x.mean == 7.5
    assert x.bin_edges == (7.5,)
    assert x.bin_counts == (40, 0)


def test_excluded_columns_are_not_profiled() -> None:
    df = pd.DataFrame({"x": [str(v) for v in range(60)], "id": [f"id{v}" for v in range(60)]})
    text = _text(df)
    schema = infer_schema(text)
    assert not schema.column("id").monitored

    profile = build_reference_profile(text, schema)

    assert set(profile.features) == {"x"}


# --- determinism (ML_MONITORING_DESIGN.md section 8) -----------------------


def test_same_inputs_give_identical_profile(reference: pd.DataFrame) -> None:
    config = ProfileConfig(sample_size=500)

    assert _build(reference, config) == _build(reference, config)


def test_seed_changes_only_the_sample(reference: pd.DataFrame) -> None:
    a = _numeric(_build(reference, ProfileConfig(sample_size=500, seed=1)), "age")
    b = _numeric(_build(reference, ProfileConfig(sample_size=500, seed=2)), "age")

    assert a.sample != b.sample
    assert a.model_dump(exclude={"sample"}) == b.model_dump(exclude={"sample"})


def test_feature_sample_does_not_depend_on_other_columns(reference: pd.DataFrame) -> None:
    config = ProfileConfig(sample_size=500)
    full = _build(reference, config)
    reordered_subset = _build(reference[["credit_score", "age"]], config)

    assert reordered_subset.features["age"] == full.features["age"]


def test_profile_json_is_identical_across_processes(reference: pd.DataFrame) -> None:
    # hash() randomisation or unordered iteration would differ between processes.
    script = (
        "import numpy as np, sys\n"
        "from driftwatch_analytics.synthetic import LOAN_APPLICANT_FEATURES, generate_dataset\n"
        "from driftwatch_analytics.schema import infer_schema, read_csv_text\n"
        "from driftwatch_analytics.profiling import ProfileConfig, build_reference_profile\n"
        "df = generate_dataset(LOAN_APPLICANT_FEATURES, 3000, np.random.default_rng(5))\n"
        "t = read_csv_text(df.to_csv(index=False).encode())\n"
        "p = build_reference_profile(t, infer_schema(t), ProfileConfig(sample_size=200))\n"
        "sys.stdout.write(p.model_dump_json())\n"
    )
    outputs = {
        subprocess.run(
            [sys.executable, "-c", script], capture_output=True, text=True, check=True
        ).stdout
        for _ in range(2)
    }

    assert len(outputs) == 1


# --- serialisation and validation on load -----------------------------------


def test_json_round_trip_preserves_profile(reference: pd.DataFrame) -> None:
    profile = _build(reference, ProfileConfig(sample_size=300))

    restored = ReferenceProfile.model_validate_json(profile.model_dump_json())

    assert restored == profile
    assert isinstance(restored.dataset_schema, DatasetSchema)
    assert restored.dataset_schema.column("age").logical_type is LogicalType.NUMERIC


def _tampered(profile: ReferenceProfile, path: list[str], value: object) -> str:
    data = json.loads(profile.model_dump_json())
    node = data
    for key in path[:-1]:
        node = node[key]
    node[path[-1]] = value
    return json.dumps(data)


@pytest.mark.parametrize(
    ("path", "value", "message"),
    [
        (["features", "age", "bin_counts"], [1, 2], "len\\(bin_edges\\) \\+ 1"),
        (["features", "age", "bin_edges"], [3.0, 2.0, 1.0], "strictly increasing"),
        (["features", "age", "sample"], [3.0, 1.0], "sorted"),
        (["features", "age", "kind"], "histogram", "kind"),
        (["features", "region", "other_count"], 5, "must equal count"),
        (["n_rows"], 1, "must equal n_rows"),
        (["format_version"], 2, "format_version"),
        (["config", "n_bins"], 1, "n_bins"),
        (["unexpected"], 1, "unexpected"),
    ],
)
def test_corrupted_profiles_are_rejected_on_load(
    reference: pd.DataFrame, path: list[str], value: object, message: str
) -> None:
    profile = _build(reference, ProfileConfig(sample_size=100))

    with pytest.raises(ValidationError, match=message):
        ReferenceProfile.model_validate_json(_tampered(profile, path, value))


def test_profile_must_cover_exactly_the_monitored_columns(reference: pd.DataFrame) -> None:
    profile = _build(reference, ProfileConfig(sample_size=100))
    data = json.loads(profile.model_dump_json())
    del data["features"]["age"]

    with pytest.raises(ValidationError, match="monitored schema columns"):
        ReferenceProfile.model_validate(data)


def test_embedded_schema_is_revalidated_on_load(reference: pd.DataFrame) -> None:
    profile = _build(reference, ProfileConfig(sample_size=100))
    duplicated = json.loads(profile.model_dump_json())
    duplicated["dataset_schema"]["columns"].append(duplicated["dataset_schema"]["columns"][0])
    bad_type = json.loads(profile.model_dump_json())
    bad_type["dataset_schema"]["columns"][0]["logical_type"] = "blob"

    with pytest.raises(ValidationError, match="unique"):
        ReferenceProfile.model_validate(duplicated)
    with pytest.raises(ValidationError, match="logical_type"):
        ReferenceProfile.model_validate(bad_type)


# --- errors -----------------------------------------------------------------


def test_rejects_data_that_does_not_match_schema(reference: pd.DataFrame) -> None:
    text = _text(reference)
    schema = infer_schema(text)

    with pytest.raises(ProfileError, match="income"):
        build_reference_profile(text.drop(columns=["income"]), schema)


def test_rejects_empty_dataset(reference: pd.DataFrame) -> None:
    schema = infer_schema(_text(reference))

    with pytest.raises(ProfileError, match="no rows"):
        build_reference_profile(_text(reference.iloc[:0]), schema)


def test_rejects_monitored_column_without_valid_values() -> None:
    text = pd.DataFrame({"x": ["a", "b", "c"]}, dtype="str")
    schema = infer_schema(text, overrides={"x": LogicalType.NUMERIC})

    with pytest.raises(ProfileError, match="without any valid values: \\['x'\\]"):
        build_reference_profile(text, schema)
