"""Drift detection on real data: scikit-learn's bundled datasets.

The ``load_*`` loaders read files shipped inside scikit-learn, so these tests
run offline (unlike ``fetch_*`` loaders, which download).

Two kinds of check (ML_MONITORING_DESIGN.md section 10):

- **No-drift controls**: random halves of a dataset are exchangeable, so any
  flag is a false alarm.
- **Biased splits**: splitting by the target or by a feature creates real,
  realistic covariate shift. The ground truth for "this feature really
  changed" is an *independent* two-sample KS test on the full data (not on
  the profile's sample), plus Cohen's d for large mean differences.

Numbers observed while writing these tests are noted next to each bound.
"""

import json
from collections.abc import Callable
from typing import Any

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from scipy.stats import chi2_contingency, ks_2samp
from sklearn.datasets import load_breast_cancer, load_diabetes, load_iris, load_wine

from driftwatch_analytics.drift import (
    CategoricalDriftResult,
    FeatureDriftResult,
    QualityFlag,
    evaluate_batch,
)
from driftwatch_analytics.main import create_app
from driftwatch_analytics.profiling import build_reference_profile
from driftwatch_analytics.schema import (
    LogicalType,
    infer_schema,
    read_csv_text,
    validate_batch,
)
from driftwatch_analytics.settings import Settings
from driftwatch_analytics.synthetic import inject_missing


def _load(loader: Callable[..., Any], **kwargs: Any) -> pd.DataFrame:
    """Typed wrapper around an untyped scikit-learn loader."""
    frame = loader(as_frame=True, **kwargs).frame
    assert isinstance(frame, pd.DataFrame)
    return frame


def _text(df: pd.DataFrame) -> pd.DataFrame:
    return read_csv_text(df.to_csv(index=False).encode())


def _evaluate(reference: pd.DataFrame, current: pd.DataFrame) -> dict[str, FeatureDriftResult]:
    """Full pipeline: CSV -> schema -> profile -> validation -> evaluation."""
    reference_text = _text(reference)
    schema = infer_schema(reference_text)
    profile = build_reference_profile(reference_text, schema)
    report = validate_batch(_text(current), schema)
    return {r.name: r for r in evaluate_batch(profile, report)}


def _random_halves(df: pd.DataFrame, seed: int) -> tuple[pd.DataFrame, pd.DataFrame]:
    order = np.random.default_rng(seed).permutation(len(df))
    half = len(df) // 2
    return df.iloc[order[:half]], df.iloc[order[half:]]


def _cohens_d(a: pd.Series, b: pd.Series) -> float:
    return float(abs(b.mean() - a.mean()) / np.sqrt((a.var() + b.var()) / 2))


@pytest.fixture(scope="module")
def breast_cancer() -> pd.DataFrame:
    return _load(load_breast_cancer)


@pytest.fixture(scope="module")
def diabetes() -> pd.DataFrame:
    # Unscaled values: integer ages and a 1/2-coded sex column, as in raw data.
    return _load(load_diabetes, scaled=False)


# --- no-drift controls ---------------------------------------------------------------


@pytest.mark.parametrize(
    ("dataset", "splits", "max_rate"),
    [
        # Observed: 0 / 1,200 feature checks over 40 splits.
        ("breast_cancer", 20, 0.02),
        # Observed: 6 / 440 (1.4%) over 40 splits.
        ("diabetes", 20, 0.04),
        # Only 89 rows per side; observed 20 / 840 (2.4%) over 60 splits.
        ("wine", 30, 0.05),
    ],
)
def test_random_halves_rarely_flag_drift(dataset: str, splits: int, max_rate: float) -> None:
    df = {
        "breast_cancer": lambda: _load(load_breast_cancer),
        "diabetes": lambda: _load(load_diabetes, scaled=False),
        "wine": lambda: _load(load_wine),
    }[dataset]()
    flags = checks = 0
    for seed in range(splits):
        reference, current = _random_halves(df, seed)
        results = _evaluate(reference, current)
        flags += sum(r.drift_detected for r in results.values())
        checks += len(results)

    assert flags / checks <= max_rate, f"{flags}/{checks} false alarms"


# --- breast cancer: benign reference vs. malignant batch -----------------------------


@pytest.fixture(scope="module")
def tumour_split(breast_cancer: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    features = breast_cancer.drop(columns="target")
    return features[breast_cancer.target == 1], features[breast_cancer.target == 0]


def test_large_mean_differences_are_all_flagged(
    tumour_split: tuple[pd.DataFrame, pd.DataFrame],
) -> None:
    benign, malignant = tumour_split
    results = _evaluate(benign, malignant)

    large = [f for f in benign.columns if _cohens_d(benign[f], malignant[f]) >= 0.5]
    assert len(large) >= 20  # observed: 25 of 30 features
    assert all(results[f].drift_detected for f in large)


def test_no_flag_without_independent_evidence(
    tumour_split: tuple[pd.DataFrame, pd.DataFrame],
) -> None:
    benign, malignant = tumour_split
    results = _evaluate(benign, malignant)

    for name, result in results.items():
        p_value = ks_2samp(benign[name], malignant[name]).pvalue
        if result.drift_detected:
            assert p_value < 0.05, f"{name} flagged but full-data KS p = {p_value:.3f}"
        if p_value > 0.1:
            # Observed: 'smoothness error' (p 0.23), 'texture error' (p 0.14).
            assert not result.drift_detected, f"{name} flagged with KS p = {p_value:.3f}"


def test_shape_change_is_detected_even_when_means_match(
    tumour_split: tuple[pd.DataFrame, pd.DataFrame],
) -> None:
    # 'fractal dimension error' barely moves in mean (d 0.17) but its spread
    # shrinks to 0.69x; PSI compares whole distributions, so it is flagged.
    benign, malignant = tumour_split
    feature = "fractal dimension error"
    assert _cohens_d(benign[feature], malignant[feature]) < 0.2
    assert malignant[feature].std() / benign[feature].std() < 0.75

    assert _evaluate(benign, malignant)[feature].drift_detected


def test_real_data_shift_through_the_http_api(
    tumour_split: tuple[pd.DataFrame, pd.DataFrame],
) -> None:
    benign, malignant = tumour_split
    with TestClient(create_app(Settings())) as client:
        profile = client.post(
            "/v1/profile", files={"file": ("benign.csv", benign.to_csv(index=False), "text/csv")}
        ).json()["profile"]
        response = client.post(
            "/v1/drift",
            files={
                "profile": ("profile.json", json.dumps(profile), "application/json"),
                "current": ("malignant.csv", malignant.to_csv(index=False), "text/csv"),
            },
        )

    assert response.status_code == 200, response.text
    summary = response.json()["summary"]
    assert summary["n_features"] == 30
    assert summary["dataset_drift"] is True
    assert summary["drift_share"] > 0.8  # observed: 27 / 30


# --- diabetes: low-BMI reference vs. high-BMI batch ----------------------------------


def test_diabetes_types_are_inferred_from_raw_values(diabetes: pd.DataFrame) -> None:
    schema = infer_schema(_text(diabetes))

    sex = schema.column("sex")
    assert sex.logical_type is LogicalType.CATEGORICAL and sex.integer_coded
    others = [c for c in schema.columns if c.name != "sex"]
    assert all(c.logical_type is LogicalType.NUMERIC for c in others)


def test_diabetes_split_by_bmi(diabetes: pd.DataFrame) -> None:
    high = diabetes.bmi > diabetes.bmi.median()
    low_bmi, high_bmi = diabetes[~high], diabetes[high]

    results = _evaluate(low_bmi, high_bmi)

    # The split variable itself, and the disease-progression target that BMI
    # is known to predict.
    assert results["bmi"].drift_detected
    assert results["target"].drift_detected
    # Sex is unrelated to the split (independent chi-square p = 0.35).
    assert chi2_contingency(pd.crosstab(high, diabetes.sex)).pvalue > 0.1
    assert not results["sex"].drift_detected
    for name, result in results.items():
        if result.drift_detected and name != "sex":
            assert ks_2samp(low_bmi[name], high_bmi[name]).pvalue < 0.05


# --- iris and wine: categorical columns on real data ---------------------------------


def test_iris_species_disappearing() -> None:
    names = dict(enumerate(load_iris().target_names))
    iris = _load(load_iris)
    iris = iris.assign(species=iris.target.map(names)).drop(columns="target")

    results = _evaluate(iris, iris[iris.species != "setosa"])

    species = results["species"]
    assert species.logical_type is LogicalType.CATEGORICAL
    assert species.drift_detected
    assert isinstance(species.drift, CategoricalDriftResult)
    proportions = dict(
        zip(species.drift.categories, species.drift.current_proportions, strict=True)
    )
    assert proportions["setosa"] == 0.0
    # Setosa has much smaller petals, so petal measurements shift too.
    assert results["petal length (cm)"].drift_detected
    assert results["petal width (cm)"].drift_detected


def test_wine_class_removed() -> None:
    wine = _load(load_wine)
    schema = infer_schema(_text(wine))
    assert schema.column("target").logical_type is LogicalType.CATEGORICAL
    assert schema.column("target").integer_coded

    results = _evaluate(wine, wine[wine.target != 2])

    assert results["target"].drift_detected


# --- data quality on real data ------------------------------------------------------


def test_missing_values_in_real_batch_are_flagged_not_drift(breast_cancer: pd.DataFrame) -> None:
    reference, current = _random_halves(breast_cancer.drop(columns="target"), seed=99)
    current = inject_missing(current, "mean radius", 0.15, np.random.default_rng(1))

    radius = _evaluate(reference, current)["mean radius"]

    assert radius.flags == (QualityFlag.MISSING_RATE_CHANGE,)
    assert not radius.drift_detected
