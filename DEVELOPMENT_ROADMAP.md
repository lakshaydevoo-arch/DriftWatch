# Development Roadmap

Legend: ✅ completed · 🔄 in progress · ⏳ pending

Each task is meant to be one small, independently testable change (usually
one commit). Milestones are ordered by dependency: the statistical engine is
built first because it is the core of the project and can be tested without
any other component.

## Milestone 0 — Foundation ✅

- ✅ Inspect repository, initialise Git
- ✅ Architecture, monitoring design, roadmap, contribution guide
- ✅ Directory structure for `analytics/`, `backend/`, `frontend/`
- ✅ Root `.gitignore` and `.editorconfig`

## Milestone 1 — Analytics engine (Python) ✅

Depends on: nothing.

- ✅ 1.1 Project scaffold: `pyproject.toml`, FastAPI app with `/health`,
  pytest, ruff (lint + format), mypy
- ✅ 1.2 Seeded synthetic dataset generator with drift injection
  (mean shift, variance change, category shift, missing values)
- ✅ 1.3 Schema inference and validation (types, missing, invalid values)
  - ✅ 1.3a Strict CSV reading, value parsing, schema inference
  - ✅ 1.3b Validate current batches against a schema
- ✅ 1.4 Reference profile builder (quantile bins, category counts, seeded
  sample) + determinism tests
- ✅ 1.5 Numeric drift: PSI and KS
- ✅ 1.6 Categorical drift: Jensen–Shannon and chi-square (rare-category
  grouping, unseen categories)
- ✅ 1.7 Wasserstein (normalised) and data-quality flags
  (missing-rate change, insufficient data, constant features). Must revisit
  the 30-row minimum: no-drift PSI at 50 rows per side gives 85% false
  alarms, and JS 71% (see the 1.5 and 1.6 log entries)
  - ✅ 1.7a Normalised Wasserstein distance (reported)
  - ✅ 1.7b Per-feature evaluation with data-quality flags
  - ✅ 1.7c Sample-size-aware noise floor (approved by maintainer)
- ✅ 1.8 `/v1/profile` and `/v1/drift` endpoints with request validation +
  API tests
- ✅ 1.9 False-positive-rate tests on no-drift controls; real-data tests on
  scikit-learn bundled datasets
- ✅ 1.10 GitHub Actions workflow for `analytics` (lint, type check, tests)

## Milestone 2 — Platform backend (Spring Boot) 🔄

Depends on: Milestone 1 API contracts (1.8).

- ✅ 2.1 Maven project scaffold (Java 21), Spotless, Actuator health, CI job
- ✅ 2.2 PostgreSQL + Flyway baseline migration; Testcontainers setup
- ⏳ 2.3 Model registry API (`/api/v1/models`) with validation and
  Problem Details errors
- ⏳ 2.4 Dataset upload, SHA-256 content-addressed file store, size limits
- ⏳ 2.5 Analytics HTTP client with timeouts and error mapping
- ⏳ 2.6 Reference registration workflow (upload → profile → persist)
- ⏳ 2.7 Monitoring run lifecycle (`PENDING → RUNNING → SUCCEEDED/FAILED`)
  on a bounded executor
- ⏳ 2.8 Run results API; end-to-end integration test with a real engine

## Milestone 3 — Prediction drift and performance ⏳

- ⏳ 3.1 Prediction column handling (numeric vs. class labels)
- ⏳ 3.2 Ground-truth upload and join by `record_id`, join-rate reporting
- ⏳ 3.3 Classification and regression metrics with degradation rules
- ⏳ 3.4 Reference performance baseline

## Milestone 4 — Dashboard (React + TypeScript) ⏳

- ⏳ 4.1 Vite + React + TS scaffold, ESLint, Prettier, Vitest, CI job
- ⏳ 4.2 Typed API client for backend contracts
- ⏳ 4.3 Models list and model detail page
- ⏳ 4.4 Run detail: per-feature results table, drift flags, warnings
- ⏳ 4.5 Distribution comparison charts (reference vs. current histograms)
- ⏳ 4.6 Run history / drift over time

## Milestone 5 — Packaging and delivery ⏳

- ⏳ 5.1 Dockerfiles for each service (multi-stage, non-root)
- ⏳ 5.2 `docker-compose.yml` for local full-stack run + `.env.example`
- ⏳ 5.3 Seeded demo script that registers a model and runs drift
  monitoring end to end
- ⏳ 5.4 Release v0.1.0

## Later / under consideration

- API-key authentication
- Scheduled monitoring runs
- Benjamini–Hochberg correction; bootstrap confidence intervals
- Multivariate drift via domain classifier
- Alerting (webhook / email)
- Object storage for datasets

Items here are not commitments; each will be justified before it is built.
