# Development Log

One entry per working session, newest first.

A commit cannot contain its own hash, so each entry's hash is filled in by the
following session's commit (or read with `git log`).

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

- To be recorded in the next entry (see `git log`).

**Remaining work**

- Milestone 1, task 1.1: scaffold the `analytics` Python project.

**Open decisions (need maintainer approval)**

1. **License** — MIT (simple, permissive) or Apache-2.0 (permissive, with an
   explicit patent grant). No license file is added until this is decided.
2. **Git author name** — the global `user.name` is not set; Git derived a name
   from the system account for this commit. Set it with
   `git config --global user.name "Your Name"` for future commits.
3. **Build order** — the roadmap builds the Python analytics engine before the
   Spring Boot backend. Confirm or reorder.
