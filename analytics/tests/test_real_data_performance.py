"""Performance metrics end to end on real data: scikit-learn's bundled datasets.

A model is trained on one half of a dataset; its predictions on the other
half go through /v1/profile, /v1/performance and the join. The engine's
metrics must equal scikit-learn's on the same predictions, computed here
without DriftWatch.
"""

import json
from typing import Any

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from sklearn.datasets import load_breast_cancer, load_diabetes
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    f1_score,
    mean_absolute_error,
    precision_score,
    r2_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from driftwatch_analytics.main import create_app
from driftwatch_analytics.settings import Settings


@pytest.fixture(scope="module")
def client() -> Any:
    with TestClient(create_app(Settings())) as test_client:
        yield test_client


def _evaluate(
    client: TestClient, reference: pd.DataFrame, current: pd.DataFrame, options: dict[str, str]
) -> dict[str, Any]:
    profile = client.post(
        "/v1/profile",
        files={"file": ("ref.csv", reference.to_csv(index=False).encode(), "text/csv")},
        data={"options": json.dumps(options)},
    )
    assert profile.status_code == 200, profile.text
    target = options["target_column"]
    response = client.post(
        "/v1/performance",
        files={
            "profile": (
                "p.json",
                json.dumps(profile.json()["profile"]).encode(),
                "application/json",
            ),
            "current": (
                "c.csv",
                current.drop(columns=[target]).to_csv(index=False).encode(),
                "text/csv",
            ),
            "labels": ("l.csv", current[["id", target]].to_csv(index=False).encode(), "text/csv"),
        },
    )
    assert response.status_code == 200, response.text
    body: dict[str, Any] = response.json()
    assert body["join"]["join_rate"] == 1.0
    metrics: dict[str, Any] = body["metrics"]
    return metrics


def test_breast_cancer_logistic_regression(client: TestClient) -> None:
    x, y = load_breast_cancer(return_X_y=True, as_frame=True)
    x_ref, x_cur, y_ref, y_cur = train_test_split(x, y, test_size=0.5, random_state=0, stratify=y)
    model = make_pipeline(StandardScaler(), LogisticRegression(max_iter=1_000)).fit(x_ref, y_ref)

    def frame(features: pd.DataFrame, labels: pd.Series, prefix: str) -> pd.DataFrame:
        out = features[["mean radius", "mean texture"]].copy()
        out.insert(0, "id", [f"{prefix}{i}" for i in range(len(out))])
        out["malignant_probability"] = np.round(model.predict_proba(features)[:, 0], 6)
        out["malignant"] = (labels == 0).astype(int)  # class 0 is malignant
        return out

    reference, current = frame(x_ref, y_ref, "r"), frame(x_cur, y_cur, "c")
    metrics = _evaluate(
        client,
        reference,
        current,
        {
            "task_type": "binary_classification",
            "prediction_column": "malignant_probability",
            "target_column": "malignant",
            "record_id_column": "id",
        },
    )

    actual = current["malignant"].to_numpy() == 1
    scores = current["malignant_probability"].to_numpy()
    predicted = scores >= 0.5
    assert metrics["status"] == "evaluated"
    assert metrics["n_evaluated"] == len(current)
    m = metrics["metrics"]
    assert m["accuracy"] == pytest.approx(accuracy_score(actual, predicted))
    assert m["precision"] == pytest.approx(precision_score(actual, predicted))
    assert m["recall"] == pytest.approx(recall_score(actual, predicted))
    assert m["f1"] == pytest.approx(f1_score(actual, predicted))
    assert m["roc_auc"] == pytest.approx(roc_auc_score(actual, scores))
    assert m["roc_auc"] > 0.95  # a sane model on this dataset


def test_diabetes_linear_regression(client: TestClient) -> None:
    x, y = load_diabetes(return_X_y=True, as_frame=True)
    x_ref, x_cur, y_ref, y_cur = train_test_split(x, y, test_size=0.5, random_state=0)
    model = LinearRegression().fit(x_ref, y_ref)

    def frame(features: pd.DataFrame, labels: pd.Series, prefix: str) -> pd.DataFrame:
        out = features[["bmi", "bp"]].copy()
        out.insert(0, "id", [f"{prefix}{i}" for i in range(len(out))])
        out["predicted_progression"] = np.round(model.predict(features), 6)
        out["progression"] = labels.to_numpy()
        return out

    reference, current = frame(x_ref, y_ref, "r"), frame(x_cur, y_cur, "c")
    metrics = _evaluate(
        client,
        reference,
        current,
        {
            "task_type": "regression",
            "prediction_column": "predicted_progression",
            "target_column": "progression",
            "record_id_column": "id",
        },
    )

    truth = current["progression"].to_numpy(dtype=float)
    predicted = current["predicted_progression"].to_numpy(dtype=float)
    m = metrics["metrics"]
    assert m["mae"] == pytest.approx(mean_absolute_error(truth, predicted))
    assert m["rmse"] == pytest.approx(float(np.sqrt(np.mean((truth - predicted) ** 2))))
    assert m["r2"] == pytest.approx(r2_score(truth, predicted))
