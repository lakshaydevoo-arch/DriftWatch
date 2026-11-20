# ML Monitoring Design

Status: **Proposed** (Milestone 0). This document defines *what* DriftWatch
measures, *how*, and *where the methods break down*. Implementations must
match this document; if they need to differ, the document is updated in the
same commit.

## 1. Kinds of change we monitor

| Kind | What changes | Needs labels? | DriftWatch signal |
|------|--------------|---------------|-------------------|
| **Data (covariate) drift** | Input distribution P(X) | No | Per-feature distribution tests (§3–§4) |
| **Prediction drift** | Output distribution P(ŷ) | No | Prediction column treated as a feature (§5) |
| **Concept drift** | Relationship P(y \| X) | **Yes** | Only observable through performance degradation (§6) |
| **Data quality issues** | Missing values, wrong types, new categories | No | Validation warnings and missing-rate checks (§7) |

Important: drift in inputs does **not** necessarily mean the model got worse,
and a model can degrade with no visible input drift (pure concept drift).
DriftWatch reports these signals separately and does not merge them into one
"health score" in v1.

## 2. Reference vs. current

- **Reference dataset:** a representative sample of the data the model is
  expected to see, usually the training or validation set.
- **Current batch:** a batch of production data (e.g. one day).
- The reference is summarised once into a **reference profile** (§8) so runs
  do not reprocess the full reference file.

All tests are **univariate**: each feature is compared independently.

## 3. Numeric features

### 3.1 Population Stability Index (PSI) — default primary method

```
PSI = Σ_i (c_i − r_i) · ln(c_i / r_i)
```

`r_i`, `c_i` are the reference and current proportions in bin `i`.

- **Binning:** 10 bins from reference quantiles (deciles). The outermost bins
  are open-ended (`-∞`, `+∞`) so current values outside the reference range
  are still counted. Duplicate edges (common for discrete or heavily tied
  features) are merged, so a feature may end up with fewer bins; the actual
  bin count is reported.
- **Zero proportions:** replaced by `ε = 1e-4` before the log to avoid
  infinities. This is a smoothing choice that slightly biases PSI for bins
  with very few observations.
- **Default threshold:** `0.2`. Common industry rule of thumb:
  `< 0.1` no meaningful shift, `0.1–0.25` moderate, `> 0.25` major.

**Why PSI as the default:** it is interpretable, symmetric, used widely in
industry (credit risk especially) and works from a stored histogram, so the
full reference data is not needed at run time.

**Limitations:** the thresholds are heuristics, not significance levels; PSI
depends on the number of bins; with small current batches PSI is noisy and
biased upward.

### 3.2 Two-sample Kolmogorov–Smirnov test — reported alongside

`scipy.stats.ks_2samp`. Statistic `D` = maximum distance between the two
empirical CDFs; null hypothesis = both samples come from the same continuous
distribution.

- Uses the seeded reference sample stored in the profile (§8), capped at
  10,000 values per feature.
- **Assumptions:** independent observations, continuous distribution. With many
  ties (discrete features) the p-value is conservative.
- **Large-sample caveat:** with tens of thousands of rows, practically
  irrelevant shifts become "statistically significant" (p ≈ 0). For this
  reason the p-value is reported but PSI is the default drift flag. `D` itself
  is a usable effect size.

### 3.3 Wasserstein distance — optional

`scipy.stats.wasserstein_distance` on the reference sample vs. the current
values, normalised by the reference standard deviation so that a single
threshold (default `0.1`) can be used across features with different units.
Undefined when the reference standard deviation is 0 — such features are
reported as `CONSTANT_IN_REFERENCE`.

## 4. Categorical features

Categories are compared as strings after trimming whitespace. Case is
preserved (`"Yes"` ≠ `"yes"`) because silently merging labels hides real
data-quality problems.

### 4.1 Jensen–Shannon distance — default primary method

`scipy.spatial.distance.jensenshannon(p, q, base=2)`, bounded in `[0, 1]`.
Handles categories with zero probability on one side without smoothing,
so **unseen categories** in the current batch are measured correctly.
Default threshold: `0.1`.

### 4.2 Chi-square test of homogeneity — reported alongside

`scipy.stats.chi2_contingency` on a 2 × k table of reference vs. current
counts.

- **Assumption:** expected count ≥ 5 in each cell. Categories below this are
  merged into a single `__other__` bucket before testing; if the test is still
  invalid, the p-value is omitted and a warning is returned.
- Same large-sample caveat as KS.

### 4.3 High cardinality

Features with more than 100 distinct reference values (configurable) are kept
as the top 100 categories by reference frequency plus `__other__`. Features
that look like identifiers (distinct ratio > 0.9) are flagged
`LIKELY_IDENTIFIER` and excluded from drift by default.

## 5. Prediction drift

The model's prediction column is profiled like any other feature:

- Regression outputs and predicted probabilities → numeric methods (§3).
- Predicted class labels → categorical methods (§4).

Prediction drift is reported separately from input drift because it is often
the earliest label-free warning that model behaviour has changed.

## 6. Performance monitoring (requires labels)

Labels are joined to predictions by a client-supplied `record_id`. Metrics are
computed only on joined rows, and the **join rate** is always reported, since
metrics on 5% of the batch are not comparable to metrics on 100%.

| Task | Metrics (scikit-learn) |
|------|------------------------|
| Binary classification | accuracy, precision, recall, F1, ROC AUC (if probabilities are available) |
| Multiclass classification | accuracy, macro F1, weighted F1 |
| Regression | MAE, RMSE, R² |

**Degradation rule (v1):** a metric is flagged when it worsens relative to the
reference baseline by more than a configurable tolerance (default 5%
relative). The baseline is computed on the reference dataset if it contains
both target and prediction columns.

**Limitation:** no confidence intervals in v1, so small batches can trigger
false alarms. Bootstrap confidence intervals are on the roadmap.

## 7. Data validation and quality

Validation happens *before* any statistics are computed.

| Situation | Behaviour |
|-----------|-----------|
| Column in schema missing from current batch | Batch rejected (`422`), listing missing columns |
| Extra column in current batch | Ignored, warning returned |
| Numeric column contains unparseable values | Values counted as `invalid`; feature marked `SCHEMA_VIOLATION` if invalid rate > 5% |
| Missing values (`NaN`, empty string, `null`) | Excluded from distribution tests; missing rate reported for both sides |
| Missing-rate change | Separate flag when absolute change > 0.05 (configurable) |
| Fewer than 30 non-missing current values | Feature marked `INSUFFICIENT_DATA`; no drift verdict |
| Constant feature in reference | Marked `CONSTANT_IN_REFERENCE`; PSI/JS still computed, Wasserstein skipped |
| Datetime / free text columns | Inferred as unsupported in v1, excluded with a warning |

**Schema inference** (on the reference dataset): a column is numeric if
≥ 95% of non-missing values parse as numbers; boolean if values are a subset
of {true, false, 0, 1, yes, no}; numeric columns with ≤ 10 distinct integer
values are treated as categorical. Users can override inferred types.

Rows are never dropped silently: every exclusion is counted and returned.

## 8. Reproducibility

A run must be reproducible from its stored inputs. Each run records:

- SHA-256 of reference and current files,
- the full run configuration (methods, thresholds, bin counts, seed),
- the analytics engine version and NumPy/SciPy/Pandas versions.

The **reference profile** is deterministic:

- quantile bin edges with a fixed interpolation method (`numpy.quantile`,
  `method="linear"`),
- category counts sorted by value,
- reference sample drawn with a fixed seed (default `42`, stored in config).

Re-running the same profile + batch + config + engine version must produce
identical results; this is enforced by tests.

## 9. Limitations

- **Univariate only.** Changes in correlations between features (joint
  distribution) are not detected in v1. Candidate future method: a domain
  classifier ("can a model tell reference from current?").
- **Multiple comparisons.** Monitoring 50 features at α = 0.05 will produce
  false positives. v1 reports a dataset-level drift share (fraction of
  features flagged, default alert at ≥ 50%) rather than a corrected test.
  Benjamini–Hochberg correction is a planned option.
- **i.i.d. assumption.** Tests assume rows are independent; time-series or
  grouped data violate this and inflate significance.
- **Thresholds are heuristics** and should be tuned per feature using
  historical no-drift batches.
- **In-memory processing** limits batch size (initial cap: 100 MB per file).
- **Sampled reference** for KS/Wasserstein reduces statistical power when the
  reference has far more than 10,000 rows.

## 10. How the methods are validated

Every method gets tests on data where the right answer is known:

- **No-drift controls:** reference and current drawn from the same
  distribution with different seeds. Used to check false-positive rates over
  repeated trials, not just a single lucky seed.
- **Injected drift:** mean shift, variance change, category proportion shift,
  new categories, injected missing values — with expected direction and
  approximate magnitude asserted.
- **Real data:** scikit-learn's bundled datasets (e.g. breast cancer, diabetes,
  wine — available offline) split in a biased way (e.g. by a feature
  threshold) to create realistic covariate shift.
- **Edge cases:** empty batches, all-missing columns, constant columns, a
  single category, unseen categories, unparseable values.

All synthetic generators live in the repository
(`analytics/src/driftwatch_analytics/synthetic/`) and take an explicit seeded
`numpy.random.Generator`, so test data is reproducible and nothing is
hardcoded.
