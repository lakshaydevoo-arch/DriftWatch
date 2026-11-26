"""Prediction drift and target handling through /v1/profile and /v1/drift (design §5)."""

import json
from typing import Any

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from driftwatch_analytics.main import create_app
from driftwatch_analytics.settings import Settings
from driftwatch_analytics.synthetic import LOAN_APPLICANT_FEATURES, generate_dataset

OPTIONS = {
    "task_type": "binary_classification",
    "prediction_column": "score",
    "target_column": "defaulted",
}


def _scored(n: int, seed: int, score_shift: float = 0.0) -> pd.DataFrame:
    """Inputs plus a probability score and a 0/1 label drawn from it."""
    rng = np.random.default_rng(seed)
    df = generate_dataset(LOAN_APPLICANT_FEATURES, n, rng)
    logit = rng.normal(-1.0 + score_shift, 1.0, n)
    score = 1 / (1 + np.exp(-logit))
    df["score"] = np.round(score, 4)
    df["defaulted"] = (rng.random(n) < score).astype(int)
    return df


def _csv(df: pd.DataFrame) -> bytes:
    return df.to_csv(index=False).encode()


@pytest.fixture(scope="module")
def client() -> Any:
    with TestClient(create_app(Settings())) as test_client:
        yield test_client


def _profile(client: TestClient, df: pd.DataFrame, options: dict[str, Any] | None) -> Any:
    form = {"options": json.dumps(options)} if options is not None else None
    return client.post("/v1/profile", files={"file": ("ref.csv", _csv(df), "text/csv")}, data=form)


def _drift(client: TestClient, profile: bytes, current: pd.DataFrame) -> Any:
    return client.post(
        "/v1/drift",
        files={
            "profile": ("profile.json", profile, "application/json"),
            "current": ("current.csv", _csv(current), "text/csv"),
        },
    )


@pytest.fixture(scope="module")
def profile(client: TestClient) -> bytes:
    response = _profile(client, _scored(3_000, 0), OPTIONS)
    assert response.status_code == 200, response.text
    return json.dumps(response.json()["profile"]).encode()


def _columns(profile: bytes) -> dict[str, dict[str, Any]]:
    return {c["name"]: c for c in json.loads(profile)["dataset_schema"]["columns"]}


def test_the_profile_records_roles_and_types(profile: bytes) -> None:
    columns = _columns(profile)

    assert columns["score"]["role"] == "prediction"
    assert columns["score"]["logical_type"] == "numeric"  # probabilities
    assert columns["score"]["source"] == "task_type"
    assert columns["defaulted"]["role"] == "target"
    assert columns["defaulted"]["excluded"] == "target_column"
    assert columns["age"]["role"] == "feature"
    features = json.loads(profile)["features"]
    assert "score" in features
    assert "defaulted" not in features


def test_unchanged_data_has_no_prediction_drift(client: TestClient, profile: bytes) -> None:
    body = _drift(client, profile, _scored(1_000, 1)).json()

    assert body["summary"]["prediction_drift"] is False
    assert body["summary"]["dataset_drift"] is False
    # Input-feature counts exclude the prediction.
    assert body["summary"]["n_features"] == len(LOAN_APPLICANT_FEATURES)
    roles = {f["name"]: f["role"] for f in body["features"]}
    assert roles["score"] == "prediction"
    assert set(roles.values()) == {"feature", "prediction"}
    assert "defaulted" not in roles


def test_shifted_predictions_are_reported_separately_from_input_drift(
    client: TestClient, profile: bytes
) -> None:
    body = _drift(client, profile, _scored(1_000, 2, score_shift=1.5)).json()

    assert body["summary"]["prediction_drift"] is True
    assert body["summary"]["n_drifted"] == 0
    assert body["summary"]["dataset_drift"] is False
    score = next(f for f in body["features"] if f["name"] == "score")
    assert score["drift"]["kind"] == "numeric"
    assert score["drift_detected"] is True


def test_a_batch_without_labels_is_accepted_silently(client: TestClient, profile: bytes) -> None:
    response = _drift(client, profile, _scored(1_000, 3).drop(columns=["defaulted"]))

    assert response.status_code == 200, response.text
    assert response.json()["issues"] == []


def test_a_batch_without_predictions_is_rejected(client: TestClient, profile: bytes) -> None:
    response = _drift(client, profile, _scored(1_000, 4).drop(columns=["score"]))

    assert response.status_code == 422
    body = response.json()
    assert body["type"] == "urn:driftwatch:problem:schema-mismatch"
    assert [e["column"] for e in body["errors"]] == ["score"]


def test_too_few_predictions_give_no_prediction_verdict(client: TestClient, profile: bytes) -> None:
    current = _scored(1_000, 5)
    current.loc[current.index[20:], "score"] = np.nan  # written as empty cells

    assert _drift(client, profile, current).json()["summary"]["prediction_drift"] is None


def test_without_a_prediction_column_prediction_drift_is_null(client: TestClient) -> None:
    response = _profile(client, _scored(500, 6), None)
    profile = json.dumps(response.json()["profile"]).encode()

    assert _drift(client, profile, _scored(500, 7)).json()["summary"]["prediction_drift"] is None


def test_class_label_predictions_use_categorical_methods(client: TestClient) -> None:
    reference = _scored(2_000, 8)
    reference["score"] = np.where(reference["score"] > 0.5, "approve", "deny")
    response = _profile(client, reference, OPTIONS)
    assert response.status_code == 200, response.text
    profile = json.dumps(response.json()["profile"]).encode()
    assert _columns(profile)["score"]["logical_type"] == "categorical"

    current = _scored(1_000, 9)
    current["score"] = np.where(current["score"] > 0.5, "approve", "deny")
    score = next(
        f for f in _drift(client, profile, current).json()["features"] if f["name"] == "score"
    )
    assert score["drift"]["kind"] == "categorical"


@pytest.mark.parametrize(
    ("options", "message"),
    [
        ({"prediction_column": "score"}, "requires task_type"),
        ({**OPTIONS, "prediction_column": "nope"}, "not in the dataset"),
        ({**OPTIONS, "target_column": "score"}, "must be different"),
        ({**OPTIONS, "task_type": "clustering"}, "options are invalid"),
    ],
)
def test_invalid_role_options_are_rejected(
    client: TestClient, options: dict[str, Any], message: str
) -> None:
    response = _profile(client, _scored(200, 10), options)

    assert response.status_code == 422
    body = response.json()
    assert body["type"] == "urn:driftwatch:problem:invalid-options"
    assert message in body["detail"]


def test_profiles_stored_before_roles_existed_still_work(client: TestClient) -> None:
    response = _profile(client, _scored(1_000, 11), None)
    stored = response.json()["profile"]
    for column in stored["dataset_schema"]["columns"]:
        del column["role"]

    body = _drift(client, json.dumps(stored).encode(), _scored(500, 12)).json()

    assert {f["role"] for f in body["features"]} == {"feature"}
    assert body["summary"]["prediction_drift"] is None
