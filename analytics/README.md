# analytics — statistical engine

**Status:** Milestone 1 complete; Milestone 3 in progress (3.1a: prediction
and target columns). Milestone 1 implemented: service scaffold with
`/health` (1.1), the seeded synthetic data generator (1.2) and CSV loading
with schema inference and batch validation (1.3), reference profiles
(1.4), numeric drift with PSI and KS (1.5), and categorical drift with
Jensen–Shannon and chi-square (1.6), per-feature evaluation with a
sample-size-aware noise floor (1.7) and the `/v1/profile` and `/v1/drift`
endpoints (1.8).

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
| pydantic | Request/response validation; reference profile model |
| python-multipart | Multipart file uploads |
| pandas | CSV parsing, column handling |
| numpy | Quantiles, binning, sampling |
| scipy (+ scipy-stubs for mypy) | KS, chi-square, Wasserstein, Jensen–Shannon |
| scikit-learn | Bundled real datasets for tests (dev only for now); performance metrics in Milestone 3 |
| pytest, httpx2 | Tests (`httpx2` is the transport Starlette's test client requires) |
| ruff, mypy | Linting/formatting and type checking (dev only) |

Only packages that the code currently imports are declared in
[`pyproject.toml`](pyproject.toml); SciPy and scikit-learn are added by the
tasks that first need them. `pandas-stubs` is a dev dependency so strict mypy
can type-check pandas code. Python 3.12+ (tested on 3.12, 3.13 and 3.14).

## Local setup

```bash
cd analytics
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.lock   # exact versions used by CI
pip install --no-deps -e .
```

`pip install -e ".[dev]"` also works, but resolves the latest compatible
versions instead of the locked ones.

### Dependency lock

[`requirements-dev.lock`](requirements-dev.lock) pins every runtime and dev
dependency. It is resolved for all supported Python versions at once
(`--universal`), so one file serves the whole CI matrix. After changing
dependencies in `pyproject.toml`, regenerate it and commit both files:

```bash
uv pip compile pyproject.toml --extra dev --universal --python-version 3.12 \
  --no-header --annotation-style line -o requirements-dev.lock
```

## Run

```bash
uvicorn driftwatch_analytics.main:app --reload
curl localhost:8000/health
# {"status":"ok","engine_version":"0.1.0.dev0"}
```

Interactive API docs: <http://localhost:8000/docs>.

## Checks

CI ([`.github/workflows/analytics.yml`](../.github/workflows/analytics.yml))
runs these on Python 3.12, 3.13 and 3.14 for every push to `main` and every
pull request that touches `analytics/`:

```bash
pytest                 # tests; warnings are treated as errors
ruff format --check .  # formatting
ruff check .           # linting
mypy                   # strict type checking of src/, tests/ and scripts/
```

## Synthetic data (`driftwatch_analytics.synthetic`)

Generates datasets where the "right answer" for drift is known, for testing
the drift methods. Every function takes an explicit
`numpy.random.Generator`, so the same seed always gives the same data.

```python
import numpy as np
from driftwatch_analytics.synthetic import (
    LOAN_APPLICANT_FEATURES,
    generate_dataset,
    shift_mean,
    scale_std,
    resample_categories,
    inject_missing,
)

rng = np.random.default_rng(42)
reference = generate_dataset(LOAN_APPLICANT_FEATURES, 10_000, rng)

current = shift_mean(reference, "age", 5.0)  # location shift
current = scale_std(current, "credit_score", 1.5)  # spread change
current = resample_categories(  # category shift,
    current,
    "employment",  # incl. an unseen
    ("salaried", "self_employed", "unemployed", "student"),  # category
    (0.5, 0.2, 0.2, 0.1),
    rng,
)
current = inject_missing(current, "income", 0.1, rng)  # missing values
```

| Function | Effect |
|----------|--------|
| `generate_dataset(specs, n_rows, rng)` | Independent columns from `NormalFeature`, `LogNormalFeature` or `CategoricalFeature` specs |
| `shift_mean(df, col, delta)` | Adds `delta` to a numeric column |
| `scale_std(df, col, factor)` | Multiplies spread by `factor`, keeps the mean |
| `resample_categories(df, col, categories, probabilities, rng)` | Redraws non-missing values from a new distribution; new categories allowed |
| `inject_missing(df, col, rate, rng)` | Sets exactly `round(rate * n_rows)` random rows to missing |

Injectors return copies and never mutate their input. Invalid arguments
(unknown column, non-numeric column, probabilities not summing to 1, rate
outside [0, 1], non-finite values) raise errors instead of being clamped.

**Limitations:** features are independent (no correlations), and the
generator produces only clean data. Messy inputs such as unparseable numbers
are built explicitly in the tests that need them.

## Schema inference (`driftwatch_analytics.schema`)

```python
from driftwatch_analytics.schema import LogicalType, infer_schema, parse_column, read_csv_text

text = read_csv_text(open("reference.csv", "rb").read())  # all cells as strings
schema = infer_schema(
    text,
    overrides={"zip_code": LogicalType.CATEGORICAL},  # optional corrections
    exclude=["customer_id"],
)
for column in schema.columns:
    print(column.name, column.logical_type, column.excluded)

parsed = parse_column(text["income"], LogicalType.NUMERIC)
parsed.values, parsed.n_missing, parsed.n_invalid
```

| Piece | Responsibility |
|-------|----------------|
| `read_csv_text` | Strict UTF-8 CSV reading; rejects blank or duplicate headers and rows with the wrong field count (`CsvFormatError`) |
| `parse_column` | Text → typed values, with separate *missing* and *invalid* masks |
| `infer_schema` | Logical type per column (`numeric`, `categorical`, `boolean`, `datetime`, `unknown`) plus an exclusion reason where needed |
| `validate_batch` | Checks a batch against a schema; returns per-column missing/invalid counts, structured issues and the parsed values |

```python
from driftwatch_analytics.schema import validate_batch

report = validate_batch(read_csv_text(current_bytes), schema)
report.is_valid  # False only if a monitored column is missing
[(i.code, i.column, i.severity) for i in report.issues]
report.column("age").invalid_rate  # invalid / non-missing, None if undefined
report.parsed["age"].values  # typed values for the drift stage
```

Rules, thresholds and limitations:
[ML_MONITORING_DESIGN.md §7](../ML_MONITORING_DESIGN.md#7-data-validation-and-quality).
Thresholds are configurable through `InferenceConfig`.

## Reference profiles (`driftwatch_analytics.profiling`)

```python
from driftwatch_analytics.profiling import ProfileConfig, ReferenceProfile, build_reference_profile

profile = build_reference_profile(
    text, schema, ProfileConfig(n_bins=10, sample_size=10_000, seed=42)
)
stored = profile.model_dump_json()  # what the backend persists
profile = ReferenceProfile.model_validate_json(stored)  # re-validated on load
```

A profile records the engine and library versions, the config, the schema and,
per monitored feature, either a `NumericProfile` (stats, quantile bins,
sorted seeded sample) or a `CategoricalProfile` (category counts plus
`other_count`). `binning.assign_bins` / `bin_counts` are the single
implementation of the bin rule, to be reused by drift detection.

Errors (`ProfileError`): data not matching the schema, zero rows, or a
monitored column with no valid values (usually a wrong type override).

## Numeric drift (`driftwatch_analytics.drift`)

```python
from driftwatch_analytics.drift import NumericDriftConfig, numeric_drift

result = numeric_drift(profile.features["age"], current_valid_values)
result.psi, result.drift_detected  # PSI is the primary method (threshold 0.2)
result.ks_statistic, result.ks_p_value  # reported, does not set the flag
result.wasserstein_normalized  # reported; distance in reference sds (None if constant)
result.reference_proportions, result.current_proportions  # per bin, for charts
```

```python
from driftwatch_analytics.drift import categorical_drift
from driftwatch_analytics.profiling import category_labels

labels = category_labels(report.parsed["region"].values, LogicalType.CATEGORICAL)
result = categorical_drift(profile.features["region"], labels)
result.js_distance, result.drift_detected  # JS is the primary method (threshold 0.1)
result.chi2_p_value, result.chi2_pooled  # reported; None if too little data
result.categories  # bucket order; None = "other"
result.unseen_categories, result.reference_truncated
```

`category_labels` is the shared conversion (booleans become `"true"` /
`"false"`) used by both the profile and drift detection.

`current_valid_values` must exclude missing and invalid values (use
`report.parsed[name].values.dropna()` from `validate_batch`). Empty or
non-finite input raises `ValueError`. On small batches the flag uses
`effective_threshold = max(threshold, noise_floor)` (see
[§3.4](../ML_MONITORING_DESIGN.md#34-sample-size-aware-noise-floor-psi-and-jensenshannon));
both values are in the result.

## Evaluating a batch (`drift.evaluate_batch`)

The full pipeline for one current batch:

```python
from driftwatch_analytics.drift import DriftConfig, evaluate_batch

report = validate_batch(read_csv_text(current_bytes), profile.dataset_schema)
if report.is_valid:
    for result in evaluate_batch(profile, report, DriftConfig()):
        print(result.name, result.status, result.drift_detected, result.flags)
```

Each `FeatureDriftResult` holds the status, the drift verdict, data-quality
flags, missing and invalid rates and the method-level result
(`NumericDriftResult` or `CategoricalDriftResult`, or `None` when there is
too little data). Features with fewer than `min_valid_count` (30) valid
values get `insufficient_data`; above that, the noise floor keeps small
batches from raising false alarms.

## Scripts

```bash
python scripts/sample_size_policy.py   # reproduces the noise-floor table in the design doc
```

## API

All endpoints are internal (called by the backend, not by end users).
Interactive docs: `/docs`; machine-readable schema: `/openapi.json`.

| Method | Path | Request (multipart/form-data) | Success |
|--------|------|-------------------------------|---------|
| `GET` | `/health` | | `200 {"status": "ok", "engine_version": "..."}` |
| `POST` | `/v1/profile` | `file`: reference CSV; `options` (optional): JSON `ProfileOptions` | `200 {"profile": ReferenceProfile, "issues": [...]}` |
| `POST` | `/v1/drift` | `profile`: profile JSON **file**; `current`: current CSV; `config` (optional): JSON `DriftConfig` | `200 DriftResponse` |

The profile is sent as a file part, not a text field, because Starlette
limits ordinary form fields to 1 MB and a profile can be larger (about
180 KB per numeric feature with the default sample).

### Example

```bash
curl -F file=@reference.csv localhost:8000/v1/profile | jq .profile > profile.json
curl -F profile=@profile.json -F current=@today.csv localhost:8000/v1/drift
curl -F file=@reference.csv \
     -F 'options={"overrides": {"zip_code": "categorical"}, "exclude": ["customer_id"]}' \
     localhost:8000/v1/profile
```

### `ProfileOptions`

```json
{"overrides": {"<column>": "numeric|categorical|boolean|datetime"},
 "exclude": ["<column>"],
 "task_type": "binary_classification|multiclass_classification|regression",
 "prediction_column": "<column>",
 "target_column": "<column>",
 "profile": {"n_bins": 10, "sample_size": 10000, "max_categories": 100, "seed": 42}}
```

**Column roles** ([design §5](../ML_MONITORING_DESIGN.md#5-prediction-drift)).
Every schema column has a `role`: `feature`, `prediction` or `target`.

- `prediction_column` (needs `task_type`) is always monitored, never excluded
  by a heuristic. Its type, unless overridden: regression → `numeric`;
  classification → `numeric` if it holds probabilities (every value a number
  in [0, 1], at least one not whole), otherwise the label type (`categorical`,
  or `boolean` for `0/1`, `yes/no`, `true/false`). Its `source` is
  `task_type`. A current batch without it is rejected (`schema-mismatch`).
- `target_column` keeps its inferred type but is excluded from drift
  (`target_column`), because labels usually arrive later. A current batch
  without it is accepted with no warning.
- Contradictions are `invalid-options`: a role naming a missing column,
  `prediction_column` without `task_type`, the same column for both roles, or
  a role column that is also in `exclude`.

Profiles stored before roles existed have no `role` field; it defaults to
`feature`, so they keep working without a format change.

### `DriftResponse`

| Field | Meaning |
|-------|---------|
| `engine_version`, `profile_engine_version` | Engine that ran the comparison / built the profile (a mismatch adds a warning) |
| `config` | The full `DriftConfig` used, defaults filled in, so the run can be repeated |
| `n_rows` | Rows in the current batch |
| `summary` | Over **input** features only: `n_features`, `n_evaluated`, `n_insufficient_data`, `n_drifted`, `n_with_quality_flags`, `drift_share` (of evaluated features; `null` if none), `dataset_drift` (`drift_share >= dataset_drift_share`, default 0.5). Plus `prediction_drift`: the prediction column's verdict, `null` if there is none or it had too little data |
| `features` | One `FeatureDriftResult` per monitored column (inputs and the prediction, each with its `role`), in schema order |
| `issues` | Validation warnings (extra columns, schema violations, empty batch), each with `code`, `severity`, `message`, `column` |
| `warnings` | Run-level warnings, e.g. engine version mismatch |

### Errors

Every error is RFC 9457 `application/problem+json`:
`{"type", "title", "status", "detail", "errors"?}`. Branch on `type`:

| Status | `type` (`urn:driftwatch:problem:` + …) | When |
|--------|-------------------------------------------|------|
| 413 | `upload-too-large` | A file exceeds `DRIFTWATCH_MAX_UPLOAD_BYTES` (default 100 MB), or `Content-Length` exceeds the total for the request |
| 422 | `invalid-request` | A required part is missing (`errors` lists which) |
| 422 | `invalid-csv` | Not UTF-8, ragged rows, blank or duplicate headers, unterminated quotes |
| 422 | `empty-dataset` | Reference CSV has a header but no rows |
| 422 | `invalid-options` | Bad `options` JSON, unknown columns in overrides/exclude |
| 422 | `unprofilable-dataset` | A monitored column has no valid values (usually a wrong override) |
| 422 | `invalid-profile` | Profile JSON is malformed, from another format version, or fails consistency checks |
| 422 | `invalid-config` | Bad `config` JSON or out-of-range values |
| 422 | `schema-mismatch` | Current batch lacks monitored columns (`errors` lists them) |

`errors` is capped at 20 entries, and request inputs are never echoed back.

### Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `DRIFTWATCH_MAX_UPLOAD_BYTES` | `104857600` | Maximum size of each uploaded file |

Endpoints are synchronous functions, so FastAPI runs them in its thread pool
and CPU-bound statistics do not block the event loop.

`engine_version` comes from the installed package metadata (single source:
`pyproject.toml`). If the package is not installed it reports
`0.0.0+unknown`, which a test guards against.
