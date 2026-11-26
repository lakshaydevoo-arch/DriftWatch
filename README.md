# DriftWatch

[![analytics](https://github.com/lakshaydevoo-arch/DriftWatch/actions/workflows/analytics.yml/badge.svg)](https://github.com/lakshaydevoo-arch/DriftWatch/actions/workflows/analytics.yml)
[![backend](https://github.com/lakshaydevoo-arch/DriftWatch/actions/workflows/backend.yml/badge.svg)](https://github.com/lakshaydevoo-arch/DriftWatch/actions/workflows/backend.yml)
[![engine-contract](https://github.com/lakshaydevoo-arch/DriftWatch/actions/workflows/engine-contract.yml/badge.svg)](https://github.com/lakshaydevoo-arch/DriftWatch/actions/workflows/engine-contract.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**DriftWatch** is an open-source ML model monitoring platform. It compares
production data against a model's reference data to detect **data drift**,
**prediction drift** and **performance degradation**, using documented,
reproducible statistical methods.

> **Project status: early development (v0.1 not released yet).** The
> statistical engine (Milestone 1) and the backend API (Milestone 2) are
> complete; Milestone 3 (prediction drift and performance) is in progress and
> the dashboard comes next. Progress: [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md)
> and the [issues](https://github.com/lakshaydevoo-arch/DriftWatch/issues).

## What it does today

- Register a model (task type, target, prediction and record id columns) and
  its reference dataset (CSV); the schema is inferred and shown back.
- Upload production batches; each becomes an asynchronous **monitoring run**
  with per-feature drift results:
  - numeric: Population Stability Index (drift flag), Kolmogorov–Smirnov and
    normalised Wasserstein (reported)
  - categorical: Jensen–Shannon distance (drift flag), chi-square (reported)
  - a sample-size-aware noise floor that keeps false alarms on small batches
    at about 1–2%
- Report **prediction drift** separately from input drift.
- Flag data-quality problems: schema violations, missing-value changes,
  unseen categories, constant features.
- Upload ground-truth labels later and join them to a run by record id, with
  the join rate reported.
- Keep every run with its configuration, engine version and dataset hashes.

Coming next: performance metrics with degradation rules, a web dashboard and
a Docker Compose setup ([roadmap](DEVELOPMENT_ROADMAP.md)).

## Architecture at a glance

| Component | Tech | Role |
|-----------|------|------|
| [`backend/`](backend/) | Java 21, Spring Boot, PostgreSQL | REST API, registry, run orchestration, persistence |
| [`analytics/`](analytics/) | Python, FastAPI, Pandas, NumPy, SciPy, scikit-learn | Stateless statistical engine |
| [`frontend/`](frontend/) | React, TypeScript | Monitoring dashboard |

Details: [ARCHITECTURE.md](ARCHITECTURE.md). Statistical methods, assumptions
and limitations: [ML_MONITORING_DESIGN.md](ML_MONITORING_DESIGN.md).

## Getting started

Each module's README has setup, run and test instructions:
[analytics/README.md](analytics/README.md) (engine) and
[backend/README.md](backend/README.md) (API). A one-command Docker Compose
setup is planned (Milestone 5).

## Prerequisites

- Java 21 (JDK); Maven is downloaded by the backend's wrapper (`./mvnw`)
- Python 3.12+
- Node.js 20+ and npm
- PostgreSQL 16 (or Docker)
- Docker (for integration tests and the full-stack setup)


## Documentation

| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components, data flow, data model, key decisions |
| [ML_MONITORING_DESIGN.md](ML_MONITORING_DESIGN.md) | Drift and performance methods, assumptions, limitations |
| [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) | Completed, in-progress and pending work |
| [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) | Per-session record of what changed and how it was tested |
| [CHANGELOG.md](CHANGELOG.md) | User-facing changes per release |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Workflow, commit conventions, quality checks |

## Contributing and security

Contributions follow [CONTRIBUTING.md](CONTRIBUTING.md); planned work is
tracked in [issues](https://github.com/lakshaydevoo-arch/DriftWatch/issues).
Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © 2026 Lakshya Sharma
