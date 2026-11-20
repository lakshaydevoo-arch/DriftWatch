# Development Log

One entry per working session, newest first.

A commit cannot contain its own hash, so each entry's hash is filled in by the
following session's commit (or read with `git log`).

---

## 2026-11-21 — Milestone 1.5: numeric drift (PSI and KS)

**Feature implemented**

- `drift.population_stability_index`: PSI over bin proportions, ε-replacement
  of zero proportions.
- `drift.numeric_drift(profile, current_values, config)`: bins current values
  with the shared `profiling.binning` rule, computes PSI (sets
  `drift_detected`, threshold 0.2) and `scipy.stats.ks_2samp` against the
  profile's seeded sample (reported only). Returns a frozen Pydantic
  `NumericDriftResult` with per-bin proportions for charts.
- Added SciPy 1.18 (runtime) and scipy-stubs (dev).

**Files changed**

- `analytics/src/driftwatch_analytics/drift/{__init__,models,numeric}.py`
- `analytics/tests/drift/{__init__,test_numeric}.py`
- `analytics/pyproject.toml`, `analytics/README.md`
- `ML_MONITORING_DESIGN.md` (§3.1 reference points), `DEVELOPMENT_ROADMAP.md`,
  `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 192 passed (27 new). PSI against a hand-computed value,
  symmetry, ε handling; no-drift PSI within 5× the theoretical noise; mean
  shifts with PSI ≈ d² (0.25 not flagged, 0.5 and 1.0 flagged); PSI
  increasing with shift size; variance change; a 0.1 sd shift with KS
  p < 0.001 but PSI not flagged (large-sample caveat); out-of-range values;
  multiplicative change on a log-normal feature; **200 repeated no-drift
  trials**: 0 PSI false alarms, and KS rejections at α = 0.05 within the
  binomial range expected for uniform p-values; constant reference; capped
  KS sample; custom threshold; determinism; invalid input and config.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Issues found and fixed during verification**

- Two of my test expectations were wrong; the code was right:
  - a constant `5.0` column is inferred as an integer-coded categorical, so
    the test now overrides it to numeric;
  - I expected ×1.3 on a log-normal feature to be flagged, but it is only a
    0.33 sd shift in log space (PSI ≈ 0.09). The test now checks the
    `(ln k / σ)²` relationship for ×1.3 (not flagged) and ×2 (flagged), and
    the relationship is documented in the design doc.

**Finding that affects the design**

- While documenting PSI noise I simulated 2,000 no-drift trials with 50 rows
  on each side: mean PSI 0.48 and an 85% false-alarm rate at the 0.2
  threshold. Empty bins make the ε term dominate. The design's 30-row
  minimum (§7) is far too low for PSI. Recorded in the design doc and the
  roadmap item for 1.7, where the minimum (or a sample-size-aware threshold)
  will be chosen with tests.

**Commit hash**

- To be recorded in the next entry (see `git log`).

**Remaining work**

- 1.6 categorical drift: Jensen–Shannon distance and chi-square.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.4: reference profile builder

**Feature implemented**

- `profiling.binning`: `quantile_edges`, `assign_bins`, `bin_counts`, the
  single implementation of the right-closed quantile bin rule with open
  outer bins and merged duplicate edges.
- `profiling.models`: Pydantic `ReferenceProfile`, `NumericProfile`,
  `CategoricalProfile`, `ProfileConfig`. Frozen, `extra="forbid"`, with
  consistency validators so corrupted stored profiles are rejected on load.
- `profiling.builder.build_reference_profile`: validates the reference
  against its schema, then profiles each monitored column. Booleans are
  stored as `"true"`/`"false"` categories; high cardinality is cut to the top
  `max_categories` plus `other_count`; samples use a per-feature RNG seeded by
  `(seed, crc32(name))`.
- Declared `pydantic` as a direct dependency (it was only transitive via
  FastAPI, but is now imported directly).

**Files changed**

- `analytics/src/driftwatch_analytics/profiling/{__init__,binning,models,builder}.py`
- `analytics/tests/profiling/{__init__,test_binning,test_builder}.py`
- `analytics/pyproject.toml`, `analytics/README.md`
- `ML_MONITORING_DESIGN.md` (§3.1, §8), `ARCHITECTURE.md` (D2, D3 status),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 165 passed (40 new). Binning: equal-frequency bins, right-closed
  boundaries, out-of-range values, ties, constant data, invalid input.
  Builder: stats against pandas, sampling cap without replacement,
  high-cardinality cut-off and tie-breaking, booleans, integer-coded
  categories, constant columns, excluded columns. Determinism: equal
  profiles for equal inputs, seed affects only the sample, a feature's
  sample is independent of other columns, byte-identical JSON from two
  separate Python processes. Load validation: JSON round trip plus 11
  corrupted-profile cases, including an invalid embedded schema.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Issues found and fixed during verification**

- A boolean test used `"maybe"` in the reference, so inference (correctly)
  did not infer boolean. The test was wrong; it now sets the type explicitly.
- mypy: `PROFILE_FORMAT_VERSION` was inferred as `int`, not `Literal[1]`;
  declared as `Final`.
- A sampling assertion was too weak (an `or` made it near-vacuous); it now
  checks the sample is distinct and drawn from the real values.

**Commit hash**

- `627ce5e`

**Remaining work**

- 1.5 numeric drift: PSI and KS, using `profiling.binning` and the stored
  sample.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.3b: batch validation against a schema

**Feature implemented**

- `schema.validate_batch` returns a `BatchValidationReport`: row count,
  per-column `ColumnValidation` (missing/invalid counts and rates, schema
  violation flag), structured `ValidationIssue`s with codes and severities,
  and the parsed typed values for later drift stages.
- Only a missing *monitored* column is an error. Extra columns, missing
  excluded columns, empty batches and invalid-value rates above
  `max_invalid_rate` (default 5%) are warnings, so healthy features are still
  monitored.

**Files changed**

- `analytics/src/driftwatch_analytics/schema/{validation,__init__}.py`
- `analytics/tests/schema/test_validation.py`
- `analytics/README.md`, `ML_MONITORING_DESIGN.md` (§7 table),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 125 passed (19 new). Covers a clean batch, a drifted but
  well-formed batch (still valid), missing monitored and excluded columns,
  extra columns, column order, exact missing rates, the 5% invalid boundary
  on both sides (25 vs. 26 of 500), invalid rate measured on non-missing
  values only, boolean validation, unseen categories not treated as errors,
  empty and all-missing batches, parsed values, custom threshold,
  determinism.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Commit hash**

- `3eb1c86`

**Remaining work**

- 1.4 reference profile builder (quantile bins, category counts, seeded
  sample) with determinism tests.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.3a: CSV reading and schema inference

Task 1.3 is split in two commits: inference (this entry) and batch
validation (1.3b), so each can be reviewed and tested on its own.

**Feature implemented**

- `schema.read_csv_text`: strict UTF-8 CSV reading with Python's `csv`
  module; rejects blank or duplicate headers, ragged rows, invalid UTF-8 and
  unterminated quotes.
- `schema.parse_column`: per-type parsing with separate missing and invalid
  masks; integer-coded categoricals normalised (`3.0` → `3`).
- `schema.infer_schema`: ordered inference rules, overrides, exclusions,
  identifier detection, configurable thresholds (`InferenceConfig`).

**Design decision**

- Did not use `pandas.read_csv`: probing showed it silently pads short rows
  with empty values and renames duplicate or blank headers (`a.1`,
  `Unnamed: 1`). Python's `csv` module is slower (pure Python) but lets
  malformed files fail loudly. Fine under the v1 upload cap; revisit if
  profiling shows a problem.

**Files changed**

- `analytics/src/driftwatch_analytics/schema/{__init__,csv_reader,parsing,types,inference}.py`
- `analytics/tests/schema/{__init__,test_csv_reader,test_parsing,test_inference}.py`
- `analytics/README.md`, `ML_MONITORING_DESIGN.md` (§4.3, §7 made precise),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 106 passed (47 new). Covers CSV edge cases (quoted commas and
  newlines, BOM, CRLF, header-only, 7 malformed inputs), missing tokens vs.
  invalid values, `inf` and `1,000` invalid, ISO-only dates, integer-coded
  normalisation incl. overflow guard, every inference rule and its boundary
  (95% numeric threshold tested on both sides), overrides, exclusions,
  determinism, and a CSV round trip of the synthetic dataset.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Issues found and fixed during verification**

- `np.floor` on a Series is typed `Any` in the stubs (mypy `no-any-return`);
  replaced with `numbers % 1 == 0`.
- Added a guard so huge values like `1e300` are not treated as integers
  (they would overflow `int64`).

**Commit hash**

- `faabff3`

**Remaining work**

- 1.3b validate current batches against a schema (missing/extra columns,
  invalid rates, schema violations).

**Open decisions**: license (MIT vs Apache-2.0).

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

- `ef89481`

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
