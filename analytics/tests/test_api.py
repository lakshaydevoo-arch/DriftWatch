"""HTTP contract tests for /v1/profile and /v1/drift."""

import json
from typing import Any

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from driftwatch_analytics import ENGINE_VERSION
from driftwatch_analytics.main import create_app
from driftwatch_analytics.profiling import ReferenceProfile
from driftwatch_analytics.settings import Settings
from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    generate_dataset,
    inject_missing,
    shift_mean,
)

PROBLEM = "application/problem+json"


def _csv(df: pd.DataFrame) -> bytes:
    return df.to_csv(index=False).encode()


def _reference_df() -> pd.DataFrame:
    return generate_dataset(LOAN_APPLICANT_FEATURES, 3_000, np.random.default_rng(0))


def _current_df(seed: int = 1, n: int = 1_000) -> pd.DataFrame:
    return generate_dataset(LOAN_APPLICANT_FEATURES, n, np.random.default_rng(seed))


def _post_profile(client: TestClient, data: bytes, options: dict[str, Any] | None = None) -> Any:
    form = {"options": json.dumps(options)} if options is not None else None
    return client.post("/v1/profile", files={"file": ("ref.csv", data, "text/csv")}, data=form)


def _post_drift(
    client: TestClient, profile: bytes, current: bytes, config: dict[str, Any] | None = None
) -> Any:
    return client.post(
        "/v1/drift",
        files={
            "profile": ("profile.json", profile, "application/json"),
            "current": ("current.csv", current, "text/csv"),
        },
        data={"config": json.dumps(config)} if config is not None else None,
    )


@pytest.fixture(scope="module")
def module_client() -> Any:
    with TestClient(create_app(Settings())) as test_client:
        yield test_client


@pytest.fixture(scope="module")
def profile_json(module_client: TestClient) -> bytes:
    response = _post_profile(module_client, _csv(_reference_df()))
    assert response.status_code == 200, response.text
    return json.dumps(response.json()["profile"]).encode()


def _assert_problem(response: Any, status: int, kind: str) -> dict[str, Any]:
    assert response.status_code == status, response.text
    assert response.headers["content-type"].startswith(PROBLEM)
    body: dict[str, Any] = response.json()
    assert body["type"] == f"urn:driftwatch:problem:{kind}"
    assert body["status"] == status
    assert body["title"] and body["detail"]
    return body


# --- /v1/profile ------------------------------------------------------------------


def test_profile_returns_a_valid_reference_profile(module_client: TestClient) -> None:
    response = _post_profile(module_client, _csv(_reference_df()))

    assert response.status_code == 200
    body = response.json()
    profile = ReferenceProfile.model_validate(body["profile"])
    assert profile.engine_version == ENGINE_VERSION
    assert profile.n_rows == 3_000
    assert set(profile.features) == {"age", "income", "credit_score", "region", "employment"}
    assert body["issues"] == []


def test_profile_options_are_applied(module_client: TestClient) -> None:
    df = _reference_df().assign(zip_code=lambda d: (d.index % 7 + 10_000).astype(str))
    options = {
        "overrides": {"zip_code": "categorical"},
        "exclude": ["credit_score"],
        "profile": {"n_bins": 5, "sample_size": 100, "seed": 7},
    }

    body = _post_profile(module_client, _csv(df), options).json()

    profile = ReferenceProfile.model_validate(body["profile"])
    assert "credit_score" not in profile.features
    assert profile.dataset_schema.column("zip_code").source == "override"
    assert profile.config.n_bins == 5 and profile.config.seed == 7
    assert len(profile.features["age"].bin_counts) == 5  # type: ignore[union-attr]


def test_profile_reports_quality_issues_with_severity(module_client: TestClient) -> None:
    # An inferred column can never violate its own schema (numeric needs >= 95%
    # parseable values; a violation needs > 5% invalid), so force the type.
    text = pd.DataFrame({"flag": ["yes", "no", "maybe", "yes", "no"] * 10})

    body = _post_profile(module_client, _csv(text), {"overrides": {"flag": "boolean"}}).json()

    assert body["issues"] == [
        {
            "code": "schema_violation",
            "severity": "warning",
            "message": body["issues"][0]["message"],
            "column": "flag",
        }
    ]


@pytest.mark.parametrize(
    ("data", "kind"),
    [
        (b"", "invalid-csv"),
        (b"a,b\n1,2,3\n", "invalid-csv"),
        (b"a,a\n1,2\n", "invalid-csv"),
        (b"a,b\n\xff,1\n", "invalid-csv"),
        (b"a,b\n", "empty-dataset"),
    ],
)
def test_profile_rejects_bad_csv(module_client: TestClient, data: bytes, kind: str) -> None:
    _assert_problem(_post_profile(module_client, data), 422, kind)


@pytest.mark.parametrize(
    "options",
    [
        {"overrides": {"nope": "numeric"}},
        {"overrides": {"age": "unknown"}},
        {"overrides": {"age": "blob"}},
        {"profile": {"n_bins": 1}},
        {"unexpected": True},
    ],
)
def test_profile_rejects_invalid_options(
    module_client: TestClient, options: dict[str, Any]
) -> None:
    _assert_problem(
        _post_profile(module_client, _csv(_reference_df()), options), 422, "invalid-options"
    )


def test_profile_rejects_malformed_options_json(module_client: TestClient) -> None:
    response = module_client.post(
        "/v1/profile",
        files={"file": ("ref.csv", _csv(_reference_df()), "text/csv")},
        data={"options": "{not json"},
    )

    _assert_problem(response, 422, "invalid-options")


def test_profile_rejects_column_without_valid_values(module_client: TestClient) -> None:
    data = _csv(pd.DataFrame({"x": ["a", "b", "c"]}))

    _assert_problem(
        _post_profile(module_client, data, {"overrides": {"x": "numeric"}}),
        422,
        "unprofilable-dataset",
    )


def test_missing_file_part_is_a_problem_response(module_client: TestClient) -> None:
    body = _assert_problem(module_client.post("/v1/profile"), 422, "invalid-request")

    assert body["errors"][0]["loc"] == ["body", "file"]


# --- /v1/drift --------------------------------------------------------------------


def test_drift_on_unchanged_data(module_client: TestClient, profile_json: bytes) -> None:
    response = _post_drift(module_client, profile_json, _csv(_current_df()))

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["engine_version"] == body["profile_engine_version"] == ENGINE_VERSION
    assert body["n_rows"] == 1_000
    assert body["summary"] == {
        "n_features": 5,
        "n_evaluated": 5,
        "n_insufficient_data": 0,
        "n_drifted": 0,
        "n_with_quality_flags": 0,
        "drift_share": 0.0,
        "dataset_drift": False,
    }
    assert [f["name"] for f in body["features"]] == [
        "age",
        "income",
        "credit_score",
        "region",
        "employment",
    ]
    assert body["warnings"] == [] and body["issues"] == []


def test_drift_detects_shift_and_quality_problems(
    module_client: TestClient, profile_json: bytes
) -> None:
    current = shift_mean(_current_df(), "age", 12.0)
    current = shift_mean(current, "credit_score", 70.0)
    current = shift_mean(current, "income", 30_000.0)
    current = inject_missing(current, "region", 0.2, np.random.default_rng(2))

    body = _post_drift(module_client, profile_json, _csv(current)).json()

    features = {f["name"]: f for f in body["features"]}
    assert {n for n, f in features.items() if f["drift_detected"]} == {
        "age",
        "credit_score",
        "income",
    }
    assert features["region"]["flags"] == ["missing_rate_change"]
    assert features["age"]["drift"]["kind"] == "numeric"
    assert features["region"]["drift"]["kind"] == "categorical"
    assert body["summary"]["n_drifted"] == 3
    assert body["summary"]["drift_share"] == pytest.approx(0.6)
    assert body["summary"]["dataset_drift"] is True
    assert body["summary"]["n_with_quality_flags"] == 1


def test_drift_echoes_the_full_config(module_client: TestClient, profile_json: bytes) -> None:
    config = {"numeric": {"psi_threshold": 0.05, "noise_floor": None}, "dataset_drift_share": 0.2}

    body = _post_drift(module_client, profile_json, _csv(_current_df()), config).json()

    assert body["config"]["numeric"]["psi_threshold"] == 0.05
    assert body["config"]["numeric"]["noise_floor"] is None
    assert body["config"]["categorical"]["js_threshold"] == 0.1  # defaults filled in
    assert body["config"]["dataset_drift_share"] == 0.2
    assert body["features"][0]["drift"]["effective_threshold"] == 0.05


def test_small_batch_gives_insufficient_data(
    module_client: TestClient, profile_json: bytes
) -> None:
    body = _post_drift(module_client, profile_json, _csv(_current_df(n=10))).json()

    assert body["summary"]["n_insufficient_data"] == 5
    assert body["summary"]["drift_share"] is None
    assert body["summary"]["dataset_drift"] is None
    assert all(f["drift"] is None for f in body["features"])


def test_extra_columns_are_reported_not_rejected(
    module_client: TestClient, profile_json: bytes
) -> None:
    body = _post_drift(module_client, profile_json, _csv(_current_df().assign(extra=1))).json()

    assert body["issues"] == [
        {
            "code": "extra_column",
            "severity": "warning",
            "message": "column 'extra' is not in the schema; ignored",
            "column": "extra",
        }
    ]


def test_missing_monitored_column_is_a_schema_mismatch(
    module_client: TestClient, profile_json: bytes
) -> None:
    current = _csv(_current_df().drop(columns=["age", "region"]))

    body = _assert_problem(
        _post_drift(module_client, profile_json, current), 422, "schema-mismatch"
    )

    assert {(e["code"], e["column"]) for e in body["errors"]} == {
        ("missing_column", "age"),
        ("missing_column", "region"),
    }


@pytest.mark.parametrize(
    "profile",
    [b"{not json", b"{}", b'{"format_version": 2}', b"[]"],
)
def test_invalid_profile_is_rejected(module_client: TestClient, profile: bytes) -> None:
    body = _assert_problem(
        _post_drift(module_client, profile, _csv(_current_df())), 422, "invalid-profile"
    )

    assert body["errors"]
    assert len(body["errors"]) <= 20


def test_tampered_profile_is_rejected(module_client: TestClient, profile_json: bytes) -> None:
    data = json.loads(profile_json)
    data["features"]["age"]["bin_counts"] = [1, 2]

    _assert_problem(
        _post_drift(module_client, json.dumps(data).encode(), _csv(_current_df())),
        422,
        "invalid-profile",
    )


def test_invalid_config_is_rejected(module_client: TestClient, profile_json: bytes) -> None:
    _assert_problem(
        _post_drift(module_client, profile_json, _csv(_current_df()), {"min_valid_count": 0}),
        422,
        "invalid-config",
    )


def test_invalid_current_csv_is_rejected(module_client: TestClient, profile_json: bytes) -> None:
    body = _assert_problem(
        _post_drift(module_client, profile_json, b"age,age\n1,2\n"), 422, "invalid-csv"
    )

    assert "'current'" in body["detail"]


def test_engine_version_mismatch_is_a_warning(
    module_client: TestClient, profile_json: bytes
) -> None:
    data = json.loads(profile_json)
    data["engine_version"] = "0.0.1"

    body = _post_drift(module_client, json.dumps(data).encode(), _csv(_current_df())).json()

    assert body["profile_engine_version"] == "0.0.1"
    assert len(body["warnings"]) == 1 and "0.0.1" in body["warnings"][0]


def test_identical_requests_give_identical_responses(
    module_client: TestClient, profile_json: bytes
) -> None:
    current = _csv(shift_mean(_current_df(5, n=200), "age", 6.0))

    first = _post_drift(module_client, profile_json, current)
    second = _post_drift(module_client, profile_json, current)

    assert first.content == second.content


# --- size limits ------------------------------------------------------------------


def test_oversized_file_is_rejected() -> None:
    with TestClient(create_app(Settings(max_upload_bytes=1_000))) as client:
        body = _assert_problem(
            _post_profile(client, _csv(_current_df(n=200))), 413, "upload-too-large"
        )

    assert "'file'" in body["detail"]


def test_oversized_request_is_rejected_before_reading_the_body() -> None:
    with TestClient(create_app(Settings(max_upload_bytes=1_000))) as client:
        response = client.post(
            "/v1/profile",
            content=b"x" * 10,
            headers={"content-length": str(10_000_000), "content-type": "multipart/form-data"},
        )

    _assert_problem(response, 413, "upload-too-large")


def test_settings_are_read_from_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DRIFTWATCH_MAX_UPLOAD_BYTES", "12345")
    assert Settings.from_env().max_upload_bytes == 12345

    monkeypatch.setenv("DRIFTWATCH_MAX_UPLOAD_BYTES", "lots")
    with pytest.raises(ValueError, match="integer"):
        Settings.from_env()


def test_openapi_documents_the_endpoints(module_client: TestClient) -> None:
    paths = module_client.get("/openapi.json").json()["paths"]

    assert {"/health", "/v1/profile", "/v1/drift"} <= set(paths)
