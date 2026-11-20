from importlib.metadata import version

from fastapi.testclient import TestClient

from driftwatch_analytics import ENGINE_VERSION


def test_health_reports_ok_and_engine_version(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "engine_version": ENGINE_VERSION}


def test_engine_version_comes_from_package_metadata() -> None:
    # Guards against the "0.0.0+unknown" fallback leaking into recorded runs
    # when the package is installed correctly.
    assert version("driftwatch-analytics") == ENGINE_VERSION
    assert ENGINE_VERSION != "0.0.0+unknown"


def test_health_rejects_non_get_methods(client: TestClient) -> None:
    response = client.post("/health")

    assert response.status_code == 405


def test_unknown_route_returns_404(client: TestClient) -> None:
    assert client.get("/does-not-exist").status_code == 404


def test_openapi_schema_documents_health_contract(client: TestClient) -> None:
    schema = client.get("/openapi.json").json()

    assert schema["info"]["version"] == ENGINE_VERSION
    health_schema = schema["components"]["schemas"]["HealthResponse"]
    assert set(health_schema["required"]) == {"status", "engine_version"}
