import numpy as np
import pandas as pd
import pytest

from driftwatch_analytics.drift import (
    CategoricalDriftResult,
    DriftConfig,
    FeatureDriftResult,
    FeatureStatus,
    NumericDriftResult,
    QualityFlag,
    evaluate_batch,
)
from driftwatch_analytics.profiling import ReferenceProfile, build_reference_profile
from driftwatch_analytics.schema import (
    LogicalType,
    infer_schema,
    read_csv_text,
    validate_batch,
)
from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    generate_dataset,
    inject_missing,
    resample_categories,
    shift_mean,
)

N = 5_000


def _text(df: pd.DataFrame) -> pd.DataFrame:
    return read_csv_text(df.to_csv(index=False).encode())


@pytest.fixture(scope="module")
def profile() -> ReferenceProfile:
    reference = generate_dataset(LOAN_APPLICANT_FEATURES, N, np.random.default_rng(0))
    text = _text(reference)
    return build_reference_profile(text, infer_schema(text))


def _current(seed: int = 1, n: int = N) -> pd.DataFrame:
    return generate_dataset(LOAN_APPLICANT_FEATURES, n, np.random.default_rng(seed))


def _evaluate(
    profile: ReferenceProfile, current: pd.DataFrame, config: DriftConfig | None = None
) -> dict[str, FeatureDriftResult]:
    report = validate_batch(_text(current), profile.dataset_schema)
    return {r.name: r for r in evaluate_batch(profile, report, config)}


def test_no_drift_batch_is_clean(profile: ReferenceProfile) -> None:
    results = _evaluate(profile, _current())

    assert list(results) == ["age", "income", "credit_score", "region", "employment"]
    for result in results.values():
        assert result.status is FeatureStatus.EVALUATED
        assert not result.drift_detected
        assert result.flags == ()
        assert result.drift is not None
    assert isinstance(results["age"].drift, NumericDriftResult)
    assert isinstance(results["region"].drift, CategoricalDriftResult)


def test_drift_is_attributed_to_the_right_features(profile: ReferenceProfile) -> None:
    current = shift_mean(_current(), "age", 12.0)  # 1 sd
    current = resample_categories(
        current,
        "employment",
        ("salaried", "self_employed", "unemployed"),
        (0.40, 0.20, 0.40),
        np.random.default_rng(2),
    )

    results = _evaluate(profile, current)

    drifted = {name for name, r in results.items() if r.drift_detected}
    assert drifted == {"age", "employment"}


def test_missing_values_are_a_flag_not_drift(profile: ReferenceProfile) -> None:
    current = inject_missing(_current(), "income", 0.2, np.random.default_rng(3))

    income = _evaluate(profile, current)["income"]

    assert QualityFlag.MISSING_RATE_CHANGE in income.flags
    assert not income.drift_detected  # remaining values have the same distribution
    assert income.missing_rate_reference == 0.0
    assert income.missing_rate_current == pytest.approx(0.2)
    assert income.valid_count_current == int(0.8 * N)


def test_small_missing_rate_change_is_not_flagged(profile: ReferenceProfile) -> None:
    current = inject_missing(_current(), "income", 0.04, np.random.default_rng(4))

    assert _evaluate(profile, current)["income"].flags == ()


def test_unparseable_values_are_flagged(profile: ReferenceProfile) -> None:
    text = _text(_current())
    text.loc[:499, "credit_score"] = "n/k"  # 10% invalid
    report = validate_batch(text, profile.dataset_schema)

    credit = {r.name: r for r in evaluate_batch(profile, report)}["credit_score"]

    assert QualityFlag.SCHEMA_VIOLATION in credit.flags
    assert credit.invalid_rate_current == pytest.approx(0.1)
    assert credit.status is FeatureStatus.EVALUATED  # still evaluated on valid values


def test_unseen_categories_are_flagged(profile: ReferenceProfile) -> None:
    current = resample_categories(
        _current(),
        "region",
        ("north", "south", "east", "west", "central"),
        (0.28, 0.24, 0.24, 0.19, 0.05),
        np.random.default_rng(5),
    )

    region = _evaluate(profile, current)["region"]

    assert QualityFlag.UNSEEN_CATEGORIES in region.flags
    assert isinstance(region.drift, CategoricalDriftResult)
    assert region.drift.unseen_categories == ("central",)


def test_too_few_values_give_insufficient_data(profile: ReferenceProfile) -> None:
    results = _evaluate(profile, _current(n=20))

    for result in results.values():
        assert result.status is FeatureStatus.INSUFFICIENT_DATA
        assert result.drift is None
        assert not result.drift_detected
        assert result.valid_count_current == 20


def test_insufficient_data_still_reports_quality_flags(profile: ReferenceProfile) -> None:
    current = inject_missing(_current(n=40), "age", 0.5, np.random.default_rng(6))

    age = _evaluate(profile, current)["age"]

    assert age.status is FeatureStatus.INSUFFICIENT_DATA
    assert QualityFlag.MISSING_RATE_CHANGE in age.flags


def test_min_valid_count_is_configurable(profile: ReferenceProfile) -> None:
    results = _evaluate(profile, _current(n=200), DriftConfig(min_valid_count=500))

    assert all(r.status is FeatureStatus.INSUFFICIENT_DATA for r in results.values())


def test_constant_reference_features_are_flagged() -> None:
    reference = pd.DataFrame(
        {"x": [str(v) for v in range(100)], "plan": ["basic"] * 100}, dtype="str"
    )
    schema = infer_schema(reference)
    profile = build_reference_profile(reference, schema)
    current = pd.DataFrame(
        {"x": [str(v) for v in range(100)], "plan": ["basic"] * 100}, dtype="str"
    )

    results = {r.name: r for r in evaluate_batch(profile, validate_batch(current, schema))}

    assert results["plan"].flags == (QualityFlag.CONSTANT_IN_REFERENCE,)
    assert QualityFlag.CONSTANT_IN_REFERENCE not in results["x"].flags


def test_boolean_feature_is_evaluated() -> None:
    reference = pd.DataFrame({"flag": ["yes", "no"] * 100}, dtype="str")
    schema = infer_schema(reference)
    assert schema.column("flag").logical_type is LogicalType.BOOLEAN
    profile = build_reference_profile(reference, schema)
    current = pd.DataFrame({"flag": ["true"] * 180 + ["0"] * 20}, dtype="str")

    (flag,) = evaluate_batch(profile, validate_batch(current, schema))

    assert flag.drift_detected
    assert flag.logical_type is LogicalType.BOOLEAN


def test_excluded_columns_are_not_evaluated() -> None:
    reference = pd.DataFrame(
        {"x": [str(v) for v in range(100)], "id": [f"id{v}" for v in range(100)]}, dtype="str"
    )
    schema = infer_schema(reference)
    profile = build_reference_profile(reference, schema)

    results = evaluate_batch(profile, validate_batch(reference, schema))

    assert [r.name for r in results] == ["x"]


def test_invalid_batch_is_rejected(profile: ReferenceProfile) -> None:
    report = validate_batch(_text(_current().drop(columns=["age"])), profile.dataset_schema)

    with pytest.raises(ValueError, match="invalid"):
        evaluate_batch(profile, report)


def test_report_from_a_different_schema_is_rejected(profile: ReferenceProfile) -> None:
    other = pd.DataFrame({"z": ["1.0", "2.0", "3.0"]}, dtype="str")
    report = validate_batch(other, infer_schema(other))

    with pytest.raises(ValueError, match="lacks profiled features"):
        evaluate_batch(profile, report)


def test_results_round_trip_through_json(profile: ReferenceProfile) -> None:
    current = inject_missing(
        shift_mean(_current(), "age", 5.0), "region", 0.1, np.random.default_rng(7)
    )

    for result in _evaluate(profile, current).values():
        assert FeatureDriftResult.model_validate_json(result.model_dump_json()) == result


def test_evaluation_is_deterministic(profile: ReferenceProfile) -> None:
    current = shift_mean(_current(8), "income", 1_000.0)

    assert _evaluate(profile, current) == _evaluate(profile, current)
