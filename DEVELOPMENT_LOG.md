# Development Log

One entry per working session, newest first.

A commit cannot contain its own hash, so each entry's hash is filled in by the
following session's commit (or read with `git log`).

---

## 2026-11-21 — Milestone 1.2: synthetic data generator with drift injection

**Feature implemented**

- `driftwatch_analytics.synthetic`: validated feature specs
  (`NormalFeature`, `LogNormalFeature`, `CategoricalFeature`), a
  `generate_dataset` function and a sample `LOAN_APPLICANT_FEATURES` scenario.
- Drift injectors that return copies: `shift_mean`, `scale_std`,
  `resample_categories` (supports unseen categories, keeps missing positions),
  `inject_missing` (exact count).
- Added runtime dependencies NumPy 2.5 and pandas 3.1; dev dependency
  pandas-stubs for strict mypy.

**Files changed**

- `analytics/src/driftwatch_analytics/synthetic/{__init__,specs,generator,drift}.py`
- `analytics/tests/synthetic/{__init__,test_specs,test_generator,test_drift}.py`
- `analytics/pyproject.toml`, `analytics/README.md`
- `ML_MONITORING_DESIGN.md`, `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`,
  `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest` — 59 passed (54 new): same seed gives identical data, different
  seeds differ; dtypes and shape; sample moments and category proportions
  within 5 standard errors of the spec; exact effects of each injector; inputs
  never mutated; missing values preserved; invalid arguments rejected.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean after fixes.
- Manual: generated the same dataset in two separate processes; SHA-256 of
  the CSV output was identical.

**Issues found and fixed during verification**

- ruff: long lines in the tests; fixed with `ruff format`.
- mypy: `iloc` indexing with `columns.get_loc` failed strict typing in
  `inject_missing`; replaced it with a boolean mask and `Series.mask`.

**Commit hash**

- To be recorded in the next entry (see `git log`).

**Remaining work**

- 1.3 schema inference and validation.

**Open decisions**: license (MIT vs Apache-2.0); Git author name (optional).

---

## 2026-11-21 — Milestone 1.1: analytics engine scaffold

**Feature implemented**

- Python package `driftwatch-analytics` (src layout, `pyproject.toml`).
- FastAPI app factory and `GET /health` returning status and engine version.
  The engine version is read from package metadata so the version recorded
  with monitoring runs has one source of truth.
- Tooling: pytest (warnings as errors, strict markers/xfail), ruff
  (format + lint rules E/W/F/I/B/UP/N/SIM/RUF), mypy in strict mode.

**Files changed**

- `analytics/pyproject.toml`
- `analytics/src/driftwatch_analytics/{__init__,_version,main}.py`
- `analytics/src/driftwatch_analytics/api/{__init__,health}.py`
- `analytics/tests/{conftest,test_health}.py`
- `analytics/README.md`, `CONTRIBUTING.md`, `DEVELOPMENT_ROADMAP.md`,
  `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15, fresh virtualenv)

- `pytest` — 5 passed (health contract, version source, 405 on POST,
  404 on unknown route, OpenAPI schema).
- `ruff format --check .`, `ruff check .`, `mypy` (strict) — all clean.
- Manual: started `uvicorn` and called `/health` → `{"status":"ok","engine_version":"0.1.0.dev0"}`.

**Issue found and fixed during verification**

- Starlette 1.10 deprecates `httpx` for its test client in favour of
  `httpx2`; because pytest treats warnings as errors the suite failed at
  import. Switched the dev dependency to `httpx2` instead of silencing the
  warning.

**Commit hash**

- `14e185b`

**Remaining work**

- 1.2 seeded synthetic dataset generator with drift injection.
- Dependencies use compatible-release ranges (`~=`), not a lock file; exact
  pinning for CI is planned with task 1.10.

**Open decisions** — unchanged from the previous entry (license, Git author
name). Build order confirmed: analytics first.

---

## 2026-11-21 — Milestone 0: project foundation

**Feature implemented**

- Initialised the Git repository (`main` branch).
- Wrote the architecture, ML monitoring design, roadmap, contribution guide,
  changelog and this log.
- Created the module directories (`analytics/`, `backend/`, `frontend/`),
  each with a README describing its scope and planned dependencies.
- Added root `.gitignore` and `.editorconfig`.

**Files changed**

- `README.md`, `ARCHITECTURE.md`, `ML_MONITORING_DESIGN.md`,
  `DEVELOPMENT_ROADMAP.md`, `DEVELOPMENT_LOG.md`, `CHANGELOG.md`,
  `CONTRIBUTING.md`
- `analytics/README.md`, `backend/README.md`, `frontend/README.md`
- `.gitignore`, `.editorconfig`

**Tests executed**

- No application code exists yet, so there are no unit tests.
- Documentation checks: every relative Markdown link and anchor resolves to an
  existing file/heading (scripted check).
- Secret scan of staged files (keywords: password, secret, token, api key,
  private key) — no matches other than documentation prose.
- Confirmed IDE files (`.idea/`) and local tool config (`.mcp.json`) are
  ignored and not staged.

**Commit hash**

- `2837056`

**Remaining work**

- Milestone 1, task 1.1: scaffold the `analytics` Python project.

**Open decisions (need maintainer approval)**

1. **License** — MIT (simple, permissive) or Apache-2.0 (permissive, with an
   explicit patent grant). No license file is added until this is decided.
2. **Git author name** — the global `user.name` is not set; Git derived a name
   from the system account for this commit. Set it with
   `git config --global user.name "Your Name"` for future commits.
3. ~~**Build order**~~ — confirmed by maintainer: analytics first.
