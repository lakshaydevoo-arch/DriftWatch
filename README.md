# DriftWatch

**DriftWatch** is an open-source ML model monitoring platform. It compares
production data against a model's reference data to detect **data drift**,
**prediction drift** and **performance degradation**, using documented,
reproducible statistical methods.

> **Project status: early development.** Milestone 1 is complete: the
> [analytics engine](analytics/) infers schemas, builds reference profiles and
> detects numeric and categorical drift over an HTTP API. A CI workflow for
> Python 3.12–3.14 is defined (verified locally; it runs on GitHub once the
> repository is published). Milestone 2, the Spring Boot backend, has started
> with its scaffold and health endpoints; the dashboard comes later.
> See [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) for progress.

## What it will do

- Register a model and its reference dataset (CSV).
- Upload production batches and run drift checks per feature:
  - numeric: Population Stability Index, Kolmogorov–Smirnov, Wasserstein
  - categorical: Jensen–Shannon distance, chi-square test
- Detect data-quality problems: schema violations, missing-value spikes,
  unseen categories.
- Track model performance once ground-truth labels arrive.
- Store every run with its configuration and dataset hashes so results can be
  reproduced.
- Show results in a web dashboard.

## Architecture at a glance

| Component | Tech | Role |
|-----------|------|------|
| [`backend/`](backend/) | Java 21, Spring Boot, PostgreSQL | REST API, registry, run orchestration, persistence |
| [`analytics/`](analytics/) | Python, FastAPI, Pandas, NumPy, SciPy, scikit-learn | Stateless statistical engine |
| [`frontend/`](frontend/) | React, TypeScript | Monitoring dashboard |

Details: [ARCHITECTURE.md](ARCHITECTURE.md). Statistical methods, assumptions
and limitations: [ML_MONITORING_DESIGN.md](ML_MONITORING_DESIGN.md).

## Prerequisites (for upcoming milestones)

- Java 21 (JDK); Maven is downloaded by the backend's wrapper (`./mvnw`)
- Python 3.12+
- Node.js 20+ and npm
- PostgreSQL 16 (or Docker)
- Docker (for integration tests and the full-stack setup)

Setup and run instructions will be added to each module's README as the module
is implemented.

## Documentation

| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components, data flow, data model, key decisions |
| [ML_MONITORING_DESIGN.md](ML_MONITORING_DESIGN.md) | Drift and performance methods, assumptions, limitations |
| [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) | Completed, in-progress and pending work |
| [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) | Per-session record of what changed and how it was tested |
| [CHANGELOG.md](CHANGELOG.md) | User-facing changes per release |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Workflow, commit conventions, quality checks |

## License

Not yet chosen — see the open decision in
[DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md). Until a license is added, all rights
are reserved by the author.
