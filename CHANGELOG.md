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
  bins (drift flag) with a two-sample KS test reported alongside.
- `analytics`: categorical drift detection using Jensen–Shannon distance
  (drift flag) with a chi-square test reported alongside; rare categories
  are pooled for the test, and unseen categories are reported.
