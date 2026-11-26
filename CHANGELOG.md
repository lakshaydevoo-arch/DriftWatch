# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project will use [Semantic Versioning](https://semver.org/) from its
first release.

## [Unreleased]

### Added

- Project documentation: architecture, ML monitoring design, roadmap,
  development log and contribution guide.
- Repository structure for `analytics`, `backend` and `frontend` modules.
- `analytics`: FastAPI service scaffold with a `GET /health` endpoint that
  reports the engine version.
- `analytics`: seeded synthetic dataset generator with drift injection
  (mean shift, spread change, category shift incl. unseen categories,
  missing values).
- `analytics`: strict CSV reading and schema inference (numeric, categorical,
  boolean, datetime) with type overrides, column exclusion and
  identifier detection.
- `analytics`: batch validation against a schema with structured issues
  (missing/extra columns, schema violations, empty batch) and per-column
  missing and invalid rates.
- `analytics`: deterministic reference profiles (quantile bins, category
  counts, seeded per-feature samples) that serialise to JSON and are
  re-validated when loaded.
- `analytics`: numeric drift detection using PSI on the profile's quantile
  bins (drift flag) with a two-sample KS test and normalised Wasserstein
  distance reported alongside.
- `analytics`: categorical drift detection using Jensen–Shannon distance
  (drift flag) with a chi-square test reported alongside; rare categories
  are pooled for the test, and unseen categories are reported.
- `analytics`: per-feature batch evaluation combining drift results with
  data-quality flags (schema violation, missing-rate change, constant
  reference feature, unseen categories) and an insufficient-data status.
- `analytics`: sample-size-aware noise floor for PSI and Jensen–Shannon. Drift
  is flagged only above `max(threshold, 99th percentile of no-drift noise)`,
  which keeps false alarms on small batches at about 1–2%.
- `analytics`: `POST /v1/profile` and `POST /v1/drift` endpoints with
  multipart uploads, a dataset-level drift summary, upload size limits
  (`DRIFTWATCH_MAX_UPLOAD_BYTES`) and RFC 9457 problem-details errors.
- `backend`: Spring Boot 4.2 application (Java 21) with health, liveness,
  readiness and build-info endpoints; other Actuator endpoints stay unexposed.
- `backend`: PostgreSQL persistence with Flyway migrations (V1: model registry
  table); readiness reports `503` when the database is unreachable.
- `backend`: model registry API (`POST`/`GET /api/v1/models`,
  `GET /api/v1/models/{id}`) with validation, race-safe unique names and
  RFC 9457 problem-details errors.
- `backend`: content-addressed dataset file storage (SHA-256, streaming,
  size limit, read-only files) configured with
  `DRIFTWATCH_DATASETS_STORAGE_ROOT` and `DRIFTWATCH_DATASETS_MAX_UPLOAD_SIZE`.
- `backend`: analytics engine client with connect/read timeouts
  (`DRIFTWATCH_ANALYTICS_BASE_URL`) and distinct rejected / unavailable /
  protocol errors; contract against the real engine checked in CI.
- `backend`: reference registration, `POST`/`GET /api/v1/models/{id}/reference`:
  uploads a CSV, profiles it with the engine and returns the inferred schema;
  engine rejections reach the client with the engine's problem type.
- `backend`: monitoring run lifecycle (`PENDING → RUNNING → SUCCEEDED/FAILED`)
  on a bounded executor (`DRIFTWATCH_RUNS_CONCURRENCY`,
  `DRIFTWATCH_RUNS_QUEUE_CAPACITY`); runs are pinned to the reference current
  at submission, store the engine's full result or a failure reason, and runs
  left unfinished by a restart are marked failed at startup.
- `backend`: runs API: `POST /api/v1/models/{id}/runs` (`202 Accepted`,
  optional `config`) and `GET /api/v1/runs/{runId}` (status, result summary
  or error); problem types `reference-required` (409), `run-not-found` (404)
  and `run-capacity-exceeded` (503 with `Retry-After`).
- `backend`: run results: `GET /api/v1/runs/{runId}/features` (the engine's
  per-feature results, optional `drifted` filter; `409 run-not-succeeded`
  before a run succeeds) and `GET /api/v1/models/{id}/runs` (paginated run
  history, newest first).
- `analytics`: prediction and target columns (`task_type`,
  `prediction_column`, `target_column` in `ProfileOptions`). The prediction
  is always monitored, typed by task (probabilities numeric, class labels
  categorical) and reported as `summary.prediction_drift`, separately from
  input drift; the target is kept but not drift-tested, and may be absent
  from current batches. Existing profiles keep working.
- `backend`: reference registration sends the model's task type and
  prediction/target columns to the engine (clients cannot override them in
  `options`), and the reference's columns show their `role`.
- `analytics`: `record_id_column` option and `POST /v1/performance`, which
  joins late ground-truth labels to a batch by record id and reports the join
  (`join_rate` and counts of every kind of gap); duplicate ids are rejected
  (`duplicate-record-ids`) without echoing them.
- `backend`: `recordIdColumn` on models (Flyway V5), sent when profiling;
  `POST`/`GET /api/v1/runs/{runId}/labels` uploads ground-truth labels for a
  run and stores the engine's join report (`labels-not-found` 404).
