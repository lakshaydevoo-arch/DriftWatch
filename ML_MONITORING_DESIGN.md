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
  bin count is reported. Bins are right-closed, `(e_i, e_{i+1}]`, and the same
  binning code is used for the profile and for drift runs, so the two
  histograms always line up.
- **Zero proportions:** replaced by `ε = 1e-4` (on either side, without
  renormalising) before the log to avoid infinities. This is a smoothing
  choice that slightly biases PSI for bins with very few observations.
- **Default threshold:** `0.2`. Common industry rule of thumb:
  `< 0.1` no meaningful shift, `0.1–0.25` moderate, `> 0.25` major.

**Why PSI as the default:** it is interpretable, symmetric, used widely in
industry (credit risk especially) and works from a stored histogram, so the
full reference data is not needed at run time.

**Limitations:** the thresholds are heuristics, not significance levels; PSI
depends on the number of bins; with small current batches PSI is noisy and
biased upward.

**Useful reference points** (checked by tests in
`analytics/tests/drift/test_numeric.py`):

- With no drift, PSI is roughly `(bins − 1) · (1/n_ref + 1/n_cur)` for large
  samples: about 0.0036 for 10 bins and 5,000 rows on each side. For small
  samples it is much worse, because some bins end up empty and the ε term
  inflates PSI. A simulation of 2,000 no-drift trials with 50 rows on each
  side gave a mean PSI of 0.48, and **85% of batches were falsely flagged**
  at 0.2. The minimum of 30 rows in §7 is therefore not enough for PSI. Task
  1.7 must raise it or make the threshold depend on sample size.
- For normal data, a mean shift of `d` standard deviations gives
  `PSI ≈ d²`. The default threshold of 0.2 therefore flags shifts of about
  0.45 standard deviations or more.
- PSI depends only on which quantile bin a value falls into, so it reflects
  shifts *relative to the feature's spread*. On a log-normal feature with
  σ = 0.8, multiplying every value by 1.3 is a log-space shift of only
  `ln 1.3 / 0.8 ≈ 0.33` sd (PSI ≈ 0.1, not flagged); doubling every value
  (PSI ≈ 0.75) is flagged.

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

### 3.3 Wasserstein distance — reported alongside

`scipy.stats.wasserstein_distance` on the reference sample vs. the current
values, divided by the reference (population) standard deviation. The result
is unit-free and easy to read: for a pure location shift it equals the shift
in reference standard deviations (tested), and it is unchanged if a feature
is rescaled (e.g. metres vs. millimetres; tested).

Unlike PSI it uses actual distances, not just bin membership, so a large
move of a few extreme values raises it more than it raises PSI.

It is reported only and does not set the drift flag. Making it selectable as
the primary method (proposed threshold 0.1) is left for later, because it
needs its own small-sample calibration. It is undefined when the reference
standard deviation is 0 (returned as `null`; the feature is flagged
`CONSTANT_IN_REFERENCE`, §7).

## 4. Categorical features

Categories are compared as strings after trimming whitespace. Case is
preserved (`"Yes"` ≠ `"yes"`) because silently merging labels hides real
data-quality problems.

### 4.1 Jensen–Shannon distance — default primary method

`scipy.spatial.distance.jensenshannon(p, q, base=2)`, bounded in `[0, 1]`.
Handles categories with zero probability on one side without smoothing,
so **unseen categories** in the current batch are measured correctly.
Default threshold: `0.1`.

**Buckets.** Reference and current counts are aligned on an ordered list:
kept reference categories (sorted), then unseen current categories (sorted),
then an "other" bucket (label `null`) if it has any mass. Using `null`
rather than a sentinel string means a real category can never collide with
it. If the profile kept only its top categories (§4.3), a current category
outside them might be a rare reference category, so all such values go into
the "other" bucket instead of getting their own bucket. They are still
listed as unseen, with `reference_truncated = true`.

**Small samples.** Like PSI, JS distance is biased upward on small batches.
In 2,000 no-drift simulations with 4 categories the false-alarm rate at the
0.1 threshold was:

| Rows per side | 30 | 50 | 100 | 200 | 500 | 1,000 |
|---------------|----|----|-----|-----|-----|-------|
| Mean JS | 0.18 | 0.14 | 0.10 | 0.07 | 0.04 | 0.03 |
| False alarms | 85% | 71% | 44% | 14% | 0.6% | 0% |

The bias also grows with the number of categories. Task 1.7 handles the
minimum sample size for both PSI and JS.

### 4.2 Chi-square test of homogeneity — reported alongside

`scipy.stats.chi2_contingency` on a 2 × k table of reference vs. current
counts.

- **Assumption:** expected count ≥ 5 in each cell. In a 2 × k table the
  expected count of cell (i, j) is `R_i · C_j / N`, so a category is large
  enough exactly when its total `C_j ≥ 5 · N / min(R)`. Categories below that
  are pooled together; if the pool is still too small it absorbs the
  smallest remaining category until it is large enough. If fewer than two
  groups remain, the p-value is omitted and a warning is returned. The
  pooled categories are listed in the result.
- No Yates continuity correction (`correction=False`): with every expected
  count ≥ 5 the uncorrected test is adequate, and it keeps 2 × 2 tables
  consistent with larger ones.
- Same large-sample caveat as KS: a 2-point shift in one category's share is
  significant with 20,000 rows but stays below the JS threshold.

### 4.3 High cardinality

Features with more than 100 distinct reference values (configurable) are kept
as the top 100 categories by reference frequency plus `__other__`. Features
that look like identifiers (text columns with at least 50 non-missing values
and distinct ratio > 0.9) are flagged `LIKELY_IDENTIFIER` during schema
inference (§7) and excluded from drift by default.

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
| Monitored column missing from current batch | Batch rejected (`422`), listing missing columns |
| Excluded column missing from current batch | Warning only (it is not needed) |
| Extra column in current batch | Ignored, warning returned |
| Numeric / boolean column contains unparseable values | Values counted as `invalid`; feature marked `SCHEMA_VIOLATION` if invalid / non-missing > 5%. The batch is not rejected: other features are still monitored |
| Unseen category in a categorical column | Not a validation issue; it is a drift signal (§4) |
| Batch with zero rows | Valid with a warning; every feature ends up `INSUFFICIENT_DATA` |
| Missing values (`NaN`, empty string, `null`) | Excluded from distribution tests; missing rate reported for both sides |
| Missing-rate change | Separate flag when absolute change > 0.05 (configurable) |
| Fewer than 30 non-missing current values | Feature marked `INSUFFICIENT_DATA`; no drift verdict |
| Constant feature in reference | Marked `CONSTANT_IN_REFERENCE`; PSI/JS still computed, Wasserstein skipped |
| Datetime columns | Inferred as `datetime`, excluded in v1 (`DATETIME_NOT_SUPPORTED`) |
| Free-text columns | Treated as categorical; excluded if they look like identifiers (§4.3) |

**Reading CSV.** Files must be UTF-8 (a byte-order mark is allowed) with a
header row. Blank or duplicate column names and rows with the wrong number
of fields are rejected: the common CSV libraries silently pad or rename them,
which would hide broken input. All cells are read as text, and DriftWatch
does the type parsing itself so it can count values that fail to parse.

**Missing vs. invalid.** A cell is *missing* if, after trimming whitespace,
it is empty or one of `NA`, `N/A`, `NaN`, `null`, `None` (any case). A cell is
*invalid* if it has content that does not parse as the column's type: for
numeric columns this includes `1,000` (thousands separators) and `inf`
(infinite values break every distribution statistic). Datetimes must be
ISO 8601. Ambiguous formats such as `01/02/2024` are invalid on purpose.

**Schema inference** (on the reference dataset), applied to non-missing
values in this order:

1. no non-missing values → `unknown`, excluded (`ALL_MISSING`);
2. every value is one of true/false/yes/no/1/0 (any case) → `boolean`;
3. ≥ 95% parse as numbers → `numeric`, except integer-valued columns with
   ≤ 10 distinct values (e.g. a 1–5 rating) → `categorical`, *integer-coded*;
4. ≥ 95% parse as ISO 8601 datetimes → `datetime`, excluded in v1;
5. otherwise → `categorical`, excluded if it looks like an identifier (§4.3).

Integer-coded categoricals are normalised so `3` and `3.0` are the same
category. pandas writes integer columns that contain missing values as
floats, so without this the same data would look like new categories.

Users can override any inferred type or exclude columns explicitly.

**Known limitations of inference.** These are heuristics: a numeric ID column
(`customer_id = 1, 2, 3, …`) is inferred as numeric and will show spurious
drift unless excluded; a genuinely numeric feature with few distinct integer
values becomes categorical unless overridden; locale-specific numbers
(`1.234,5`) are invalid.

Rows are never dropped silently: every exclusion is counted and returned.

## 8. Reproducibility

A run must be reproducible from its stored inputs. Each run records:

- SHA-256 of reference and current files,
- the full run configuration (methods, thresholds, bin counts, seed),
- the analytics engine version and NumPy/SciPy/Pandas versions.

The **reference profile** is deterministic:

- quantile bin edges with a fixed interpolation method (`numpy.quantile`,
  `method="linear"`),
- category counts sorted by value; when a feature has more than
  `max_categories` values, the most frequent are kept (ties broken by value)
  and the rest are summed into a separate `other_count`, which never collides
  with a real category name,
- reference sample drawn without replacement with a fixed seed (default `42`,
  stored in config). Each feature gets its own random stream seeded with
  `(seed, crc32(feature name))`, so adding or reordering columns does not
  change another feature's sample. `crc32` is used because Python's `hash()`
  of a string differs between processes.

Profile contents per monitored feature: missing and invalid counts, and

- numeric: count, mean, population standard deviation (`ddof=0`, defined
  even for one value), min, max, constant flag, interior bin edges, bin counts
  and the sorted sample;
- categorical and boolean (stored as `"true"`/`"false"`): kept category
  counts, `other_count` and the number of distinct values.

The profile embeds the dataset schema and is re-validated when loaded
(bins strictly increasing, counts adding up, features matching the schema),
so a corrupted stored profile is rejected rather than silently used.

**Size trade-off:** with the default 10,000-value sample a numeric feature
takes about 180 KB of JSON. That is fine for tens of features; for very wide
datasets `sample_size` should be lowered, at the cost of KS/Wasserstein power.

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
