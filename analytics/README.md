# analytics — statistical engine

**Status:** not implemented (Milestone 1).

Stateless FastAPI service that infers schemas, builds reference profiles and
computes drift and performance metrics. It has no database and no filesystem
state: the same inputs and engine version always give the same output.
Methods are specified in [ML_MONITORING_DESIGN.md](../ML_MONITORING_DESIGN.md).

## Planned layout

```text
analytics/
├── pyproject.toml
├── src/driftwatch_analytics/
│   ├── api/          FastAPI routes and request/response models
│   ├── schema/       type inference and validation
│   ├── profile/      reference profile builder
│   ├── drift/        numeric and categorical drift methods
│   ├── performance/  classification and regression metrics
│   └── synthetic/    seeded dataset generators with drift injection
└── tests/
```

## Planned dependencies

| Package | Purpose |
|---------|---------|
| fastapi, uvicorn | HTTP API and ASGI server |
| pydantic | Request/response validation |
| python-multipart | CSV upload handling |
| pandas | CSV parsing, column handling |
| numpy | Quantiles, binning, sampling |
| scipy | KS, chi-square, Wasserstein, Jensen–Shannon |
| scikit-learn | Performance metrics; bundled real datasets for tests |
| pytest, httpx | Tests (including API tests via FastAPI's test client) |
| ruff, mypy | Linting/formatting and type checking (dev only) |

Versions are pinned in `pyproject.toml` when the module is scaffolded.
Python 3.12+.
