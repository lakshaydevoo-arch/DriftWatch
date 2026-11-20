# analytics — statistical engine

**Status:** scaffold only (Milestone 1, task 1.1). The service starts and
serves `/health`; no statistics are implemented yet.

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
| pytest, httpx2 | Tests (`httpx2` is the transport Starlette's test client requires) |
| ruff, mypy | Linting/formatting and type checking (dev only) |

Only packages that the code currently imports are declared in
[`pyproject.toml`](pyproject.toml); Pandas, NumPy, SciPy and scikit-learn are
added by the tasks that first need them. Python 3.12+.

## Local setup

```bash
cd analytics
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

## Run

```bash
uvicorn driftwatch_analytics.main:app --reload
curl localhost:8000/health
# {"status":"ok","engine_version":"0.1.0.dev0"}
```

Interactive API docs: <http://localhost:8000/docs>.

## Checks

```bash
pytest                 # tests; warnings are treated as errors
ruff format --check .  # formatting
ruff check .           # linting
mypy                   # strict type checking of src/ and tests/
```

## API

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/health` | `200 {"status": "ok", "engine_version": "<version>"}` |

`engine_version` comes from the installed package metadata (single source:
`pyproject.toml`). If the package is not installed it reports
`0.0.0+unknown`, which a test guards against.
