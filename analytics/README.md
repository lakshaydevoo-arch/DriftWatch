# analytics — statistical engine

**Status:** Milestone 1 in progress. Implemented: service scaffold with
`/health` (1.1), the seeded synthetic data generator (1.2) and CSV loading
with schema inference and batch validation (1.3), and reference profiles
(1.4), numeric drift with PSI and KS (1.5), and categorical drift with
Jensen–Shannon and chi-square (1.6).

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
| python-multipart | CSV upload handling |
| pandas | CSV parsing, column handling |
| numpy | Quantiles, binning, sampling |
| scipy (+ scipy-stubs for mypy) | KS, chi-square, Wasserstein, Jensen–Shannon |
| scikit-learn | Performance metrics; bundled real datasets for tests |
| pytest, httpx2 | Tests (`httpx2` is the transport Starlette's test client requires) |
| ruff, mypy | Linting/formatting and type checking (dev only) |

Only packages that the code currently imports are declared in
[`pyproject.toml`](pyproject.toml); SciPy and scikit-learn are added by the
tasks that first need them. `pandas-stubs` is a dev dependency so strict mypy
can type-check pandas code. Python 3.12+.

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
non-finite input raises `ValueError`; minimum-sample-size handling comes
with task 1.7.

## API

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/health` | `200 {"status": "ok", "engine_version": "<version>"}` |

`engine_version` comes from the installed package metadata (single source:
`pyproject.toml`). If the package is not installed it reports
`0.0.0+unknown`, which a test guards against.
