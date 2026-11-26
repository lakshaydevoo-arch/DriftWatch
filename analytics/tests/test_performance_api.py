"""POST /v1/performance: joining labels over HTTP."""

import json
from typing import Any

import pytest
from fastapi.testclient import TestClient

from driftwatch_analytics import ENGINE_VERSION
from driftwatch_analytics.main import create_app
from driftwatch_analytics.settings import Settings

REFERENCE = "id,x,score,label\n" + "".join(
    f"r{i},{i * 1.5},{(i % 10) / 10 + 0.05:.2f},{i % 2}\n" for i in range(200)
)
OPTIONS = {
    "task_type": "binary_classification",
    "prediction_column": "score",
    "target_column": "label",
    "record_id_column": "id",
}


@pytest.fixture(scope="module")
def client() -> Any:
    with TestClient(create_app(Settings())) as test_client:
        yield test_client


def _profile(client: TestClient, options: dict[str, Any]) -> bytes:
    response = client.post(
        "/v1/profile",
        files={"file": ("ref.csv", REFERENCE.encode(), "text/csv")},
        data={"options": json.dumps(options)},
    )
    assert response.status_code == 200, response.text
    return json.dumps(response.json()["profile"]).encode()


@pytest.fixture(scope="module")
def profile(client: TestClient) -> bytes:
    return _profile(client, OPTIONS)


def _post(client: TestClient, profile: bytes, current: str, labels: str) -> Any:
    return client.post(
        "/v1/performance",
        files={
            "profile": ("profile.json", profile, "application/json"),
            "current": ("current.csv", current.encode(), "text/csv"),
            "labels": ("labels.csv", labels.encode(), "text/csv"),
        },
    )


CURRENT = "id,x,score\na,1,0.2\nb,2,0.8\nc,3,0.5\nd,4,0.9\n"


def test_the_join_report_is_returned(client: TestClient, profile: bytes) -> None:
    response = _post(client, profile, CURRENT, "id,label\nb,1\na,0\nzzz,1\n")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["engine_version"] == body["profile_engine_version"] == ENGINE_VERSION
    assert body["join"] == {
        "n_current_rows": 4,
        "n_label_rows": 3,
        "n_current_without_id": 0,
        "n_labels_without_id": 0,
        "n_matched": 2,
        "n_missing_labels": 0,
        "n_invalid_labels": 0,
        "n_joined": 2,
        "n_unmatched_labels": 1,
        "join_rate": 0.5,
    }


def test_the_profile_marks_the_record_id(profile: bytes) -> None:
    columns = {c["name"]: c for c in json.loads(profile)["dataset_schema"]["columns"]}

    assert columns["id"]["role"] == "record_id"
    assert columns["id"]["excluded"] == "record_id"
    assert "id" not in json.loads(profile)["features"]


def test_drift_does_not_need_or_warn_about_the_record_id(
    client: TestClient, profile: bytes
) -> None:
    current = "x,score\n" + "".join(f"{i},{(i % 10) / 10 + 0.05:.2f}\n" for i in range(100))
    response = client.post(
        "/v1/drift",
        files={
            "profile": ("profile.json", profile, "application/json"),
            "current": ("current.csv", current.encode(), "text/csv"),
        },
    )

    assert response.status_code == 200, response.text
    # Drift still runs; the warning says what the missing id costs.
    [issue] = response.json()["issues"]
    assert issue["code"] == "missing_excluded_column"
    assert issue["severity"] == "warning"
    assert "labels cannot be joined" in issue["message"]


def _problem(response: Any, kind: str) -> dict[str, Any]:
    assert response.status_code == 422, response.text
    assert response.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = response.json()
    assert body["type"] == f"urn:driftwatch:problem:{kind}"
    return body


def test_duplicate_ids_are_rejected(client: TestClient, profile: bytes) -> None:
    body = _problem(_post(client, profile, CURRENT, "id,label\na,1\na,0\n"), "duplicate-record-ids")

    assert body["errors"] == [{"part": "labels", "n_duplicated_ids": 1}]


def test_labels_without_the_target_column_are_rejected(client: TestClient, profile: bytes) -> None:
    body = _problem(_post(client, profile, CURRENT, "id,outcome\na,1\n"), "schema-mismatch")

    assert body["errors"] == [{"part": "labels", "column": "label"}]


def test_a_profile_without_roles_cannot_join(client: TestClient) -> None:
    plain = _profile(client, {})

    body = _problem(_post(client, plain, CURRENT, "id,label\na,1\n"), "labels-not-configured")

    assert body["errors"] == [{"missing": "target_column"}, {"missing": "record_id_column"}]


def test_a_broken_labels_csv_is_invalid_csv(client: TestClient, profile: bytes) -> None:
    _problem(_post(client, profile, CURRENT, "id,label\na,1,extra\n"), "invalid-csv")


def test_a_missing_labels_part_is_invalid_request(client: TestClient, profile: bytes) -> None:
    response = client.post(
        "/v1/performance",
        files={
            "profile": ("profile.json", profile, "application/json"),
            "current": ("current.csv", CURRENT.encode(), "text/csv"),
        },
    )

    _problem(response, "invalid-request")
