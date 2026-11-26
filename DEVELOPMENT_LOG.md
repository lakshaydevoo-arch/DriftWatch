# Development Log

One entry per working session, newest first.

A commit cannot contain its own hash, so each entry's hash is filled in by the
following session's commit (or read with `git log`).

---

## 2026-11-26 — Milestone 2.7a: monitoring run lifecycle

**Feature implemented**

- `V4__create_monitoring_run.sql`: `monitoring_run` with status, the
  requested config, the engine's full result (`JSONB`), failure fields
  (`error_kind`, `error_message`, `error_details`) and timestamps. One check
  constraint ties each status to exactly its fields (a `SUCCEEDED` run has a
  result and engine version; a `FAILED` run has a reason and no result; a
  `PENDING` run has not started). Composite foreign keys make the compared
  dataset a `CURRENT` dataset of the same model and the reference a reference
  of the same model; each batch belongs to one run.
- `RunService.submit`: reserve executor capacity, pin the current reference,
  store the file, insert dataset + `PENDING` run in one transaction, then
  queue the run after the commit.
- `RunExecutor`: fixed worker pool plus a semaphore of
  `concurrency + queue-capacity` slots, reserved *before* anything is stored,
  so a refused submission leaves no file, dataset or run behind.
- `RunWorker`: claims the run with a conditional `UPDATE … WHERE status =
  'PENDING'` (a run starts at most once), calls `/v1/drift` with no
  transaction open, validates the response's required fields and stores the
  result or a failure reason in one transaction. No automatic retries.
- `RunRecovery`: at startup, before the web server accepts requests, marks
  `PENDING`/`RUNNING` runs `FAILED` (`interrupted`).
- `ReferenceService.currentId` (id only, without loading the multi-MB
  profile) and `get`; `DatasetService.resource(Dataset)`.
- HTTP endpoints are deliberately left for 2.7b so this commit stays one
  reviewable piece (persistence and concurrency).

**Why this design**

- *Problem:* drift on a 100 MB batch can take minutes; an HTTP request
  should not wait that long, and the result must survive the request.
- *Async with a stored state machine:* the client gets a run id at once and
  polls. Every terminal state is complete by construction (database checks),
  so a run can never look finished with half a result.
- *Bounded:* each run holds a whole batch in the engine's memory. Two workers
  and a 20-run queue by default; beyond that the backend says "try later"
  rather than queueing without limit.
- *Results as JSON (D7, changes the architecture sketch):* the planned
  `feature_drift_result` table was dropped. Results are always read per run,
  and the engine owns their shape; storing them whole mirrors the reference
  profile and needs no migration when the engine adds a field.
- *Fail, don't resume, after a restart (D8):* re-queueing could loop on a
  batch that crashes the engine; failing tells the client what happened.

**Trade-offs and failure cases**

- Single instance only: a second instance starting up would fail the first
  one's in-flight runs. Needs a lease/owner column before scaling out.
- A restart loses queued runs (they become `FAILED`, `interrupted`).
- If the database is down when a worker stores the outcome, the run stays
  `RUNNING` until the next start, when recovery fails it.
- The engine validates `config` only when the run executes, so a bad config
  becomes a `FAILED` run (`invalid-config`) rather than an immediate `400`.
- Cross-run per-feature queries (drift over time) need JSONB operators.

**Files changed**

- `backend/src/main/resources/db/migration/V4__create_monitoring_run.sql`
- `backend/src/main/java/io/driftwatch/backend/run/` (new):
  `MonitoringRun`, `MonitoringRunRepository`, `RunStatus`, `RunService`,
  `RunWorker`, `RunExecutor`, `RunRecovery`, `RunConfiguration`,
  `RunProperties`, `RunCapacityExceededException`, `RunNotFoundException`,
  `ReferenceRequiredException`
- `backend/src/main/java/io/driftwatch/backend/reference/ReferenceService.java`,
  `ReferenceRepository.java`; `dataset/DatasetService.java`
- `backend/src/main/resources/application.yml` (`driftwatch.runs.*`)
- Tests: `run/RunLifecycleTest`, `run/RunCapacityTest`,
  `run/MonitoringRunSchemaTest`, `run/RunEngineContractTest`, `run/RunFixtures`;
  `DatabaseSchemaTest` (V4 in history), `TestDatabase` (deletes runs first)
- Docs: `backend/README.md`, `ARCHITECTURE.md`, `DEVELOPMENT_ROADMAP.md`,
  `CHANGELOG.md`, this log

**Tests executed**

- `./mvnw verify`: 164 passed, 12 skipped (engine-contract tests, gated on
  `DRIFTWATCH_ANALYTICS_URL`), Spotless clean.
- Engine-contract tests against a locally started real engine: 14 passed,
  including the new `RunEngineContractTest` (a +15 shift in `age` is flagged,
  the unshifted `region` is not; a batch without `age` fails with
  `schema-mismatch`).
- Flakiness: `RunLifecycleTest` + `RunCapacityTest` repeated 5 times, and the
  full suite in reverse order (166 passed with the engine running); all green.
- One test expectation was wrong and fixed: an unknown status violates both
  the status and the state check, and PostgreSQL reports the state check
  first; the test now asserts a check-constraint violation.

**Commit hash**

- To be recorded in the next entry (see `git log`).

**Remaining work**

- 2.7b: `POST /api/v1/models/{id}/runs` (multipart `current` + optional
  `config`, `202 Accepted`), `GET /api/v1/runs/{runId}`, problem types
  (`run-capacity-exceeded` 503, `reference-required` 409, `run-not-found`
  404), API tests and an HTTP engine-contract test.
- 2.8: per-feature results and run history.
- License decision (open).

---

## 2026-11-22 — Publish to GitHub

**What changed**

- Published the repository at
  <https://github.com/lakshaydevoo-arch/DriftWatch> (public), with the
  maintainer's approval.
- Before the first push, the author and committer email of all 22 local
  commits was changed to the maintainer's GitHub noreply address, with the
  maintainer's approval. GitHub refused the push otherwise (its email-privacy
  protection). Nothing had been published, so no one else's history depends
  on the old hashes. Content, messages and dates are unchanged; the final tree
  is identical. The pre-rewrite history is kept locally on the branch
  `backup/pre-noreply-email`, which is not pushed.
- This repository's `user.email` now uses the noreply address.
- At the maintainer's request, the `Co-Authored-By: Claude` trailer was
  removed from every commit message, so the AI assistant is not listed as a
  repository contributor (it is credited elsewhere, as the maintainer
  decides). This needed one force-push of `main`, approved by the
  maintainer, minutes after the first push and before anyone else could
  have cloned it. Code, dates, authors and subjects are unchanged. The
  history before this step is kept locally on `backup/pre-remove-coauthor`
  (not pushed). Future commits do not carry the trailer.
- Every commit hash in this log was updated to the final rewritten hash
  (23 references). Old → new:

  - `2837056` → `370c809`
  - `14e185b` → `8caa76b`
  - `ef89481` → `35c5b9a`
  - `faabff3` → `2056842`
  - `3eb1c86` → `c4dc3bb`
  - `627ce5e` → `b6211ff`
  - `3176f63` → `231136a`
  - `16d48c6` → `86d70a8`
  - `0df8853` → `4163004`
  - `f50d149` → `2144b9c`
  - `25ac76b` → `ef2cd9c`
  - `1d5151d` → `62b0c59`
  - `b26ab31` → `11a7aed`
  - `979676f` → `1c12c46`
  - `86f6b4c` → `ae827f6`
  - `f938d2e` → `5f6a08a`
  - `f97372f` → `0e04631`
  - `6742520` → `139fd24`
  - `a16d2e9` → `a62547a`
  - `3560eac` → `eebaef8`
  - `c33fa43` → `7a08deb`
  - `aa32835` → `5ffdb81`

**Files changed**

- `DEVELOPMENT_LOG.md`

**Checks**

- First CI runs on GitHub (manually dispatched, since the triggering
  commit was docs-only): `analytics`, `backend` and `engine-contract` all
  passed; re-run after the force-push.
- History scanned for tokens, keys, passwords, `.env` files and large files
  before publishing: none found.
- No code changed, so tests were not re-run for this entry; CI runs on GitHub
  for the first time with this push.

**Commit hash**

- `9147086` (noreply hash update) + `52496a6` (co-author removal hash update)

**Remaining work**

- Choose a license (MIT or Apache-2.0).
- Task 2.7: monitoring run lifecycle.

---

## 2026-11-21 — Milestone 2.6: reference registration workflow

**Feature implemented**

- `POST /api/v1/models/{id}/reference` (multipart `file` + optional JSON
  `options`) and `GET /api/v1/models/{id}/reference` (current reference).
  Responses include the inferred columns, row count, engine version, issues
  and the dataset; the full profile stays in the database.
- `V3__create_reference_profile.sql`: `reference_profile` with the profile and
  issues as `JSONB` (shape checked with `jsonb_typeof`), positive row count,
  one profile per dataset, and a **composite foreign key**
  `(dataset_id, model_id, dataset_role) → dataset (id, model_id, role)`, so
  the database guarantees that a reference points at a `REFERENCE` dataset of
  the same model.
- `ReferenceService.register`: check and store the file, then profile, with
  no transaction open; then insert dataset + reference in one transaction.
  Re-registration keeps history; the newest is current.
- `DatasetService` split into `storeFile` (no transaction) and `record`
  (`Propagation.MANDATORY`, so it cannot run outside the caller's
  transaction); `ContentAddressedFileStore.resource`.
- Error mapping: engine rejections pass through with the engine's own problem
  type (422; 413 for size), unavailable → 503 + `Retry-After` (address logged,
  not returned), contract violations → 502, plus `upload-too-large`,
  `empty-upload`, `invalid-upload`, `reference-not-found` and the missing
  `file` part. Multipart limit tied to `driftwatch.datasets.max-upload-size`.
- Tests: `TestDatabase.clear` deletes tables in foreign-key order for all
  test classes; `Multipart` builds request bodies. The `engine-contract`
  workflow now runs every `*EngineContractTest`.

**Design decisions**

- **Synchronous profiling**, because registration is a one-off, interactive
  action and the user wants to see the inferred schema. Monitoring runs (2.7)
  will run in the background.
- **Write only on success.** Rows appear only after profiling succeeds, so no
  status column and no half-registered references. The cost is that failed
  attempts are not recorded in the database (they are logged and returned to
  the client).

**Files changed**

- `backend/src/main/resources/db/migration/V3__create_reference_profile.sql`
- `backend/src/main/java/io/driftwatch/backend/reference/*` (7 files)
- `backend/src/main/java/io/driftwatch/backend/dataset/{DatasetService,ContentAddressedFileStore}.java`
- `backend/src/main/java/io/driftwatch/backend/analytics/{AnalyticsProtocolException,AnalyticsRejectedException,AnalyticsUnavailableException}.java` (public constructors)
- `backend/src/main/java/io/driftwatch/backend/common/{ApiExceptionHandler,ProblemTypes}.java`
- `backend/src/main/resources/application.yml`
- Tests: `reference/{ReferenceApiTest,ReferenceUploadLimitTest,ReferenceRegistrationEngineContractTest}.java`,
  `{TestDatabase,Multipart}.java`, updated `DatabaseSchemaTest`,
  `DatasetServiceTest`, `ModelApiTest`
- `.github/workflows/engine-contract.yml`, `backend/README.md`,
  `ARCHITECTURE.md`, `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`,
  `DEVELOPMENT_LOG.md`

**Tests executed** (Temurin 21.0.13, PostgreSQL 16.16, engine 0.1.0.dev0)

- `./mvnw verify`: **124 passed, 12 skipped** (the engine contract tests), in
  both alphabetical and reverse class order.
  - `ReferenceApiTest` (20, mocked engine): 201 with `Location`, columns,
    issues and SHA-256; the engine receives exactly the uploaded bytes and the
    file name; the profile is stored unchanged and is queryable as `JSONB`; GET
    equals POST; re-registration keeps history; options forwarded or omitted;
    rejection pass-through with no rows; engine 413 → 413; 503 with
    `Retry-After` and no internal address; 502 for a contract violation and for
    a profile without `n_rows`; unknown model 404 without calling the engine;
    empty file 400 without calling the engine; missing part; malformed and
    non-object options; 415; `reference-not-found`.
  - `ReferenceUploadLimitTest` (3): file-store limit, multipart limit, exact
    limit accepted.
- **Against the real engine** (`*EngineContractTest`): **12 passed**: end to
  end registration with inferred numeric/categorical/identifier columns, GET
  equal to POST, `JSONB` profile queried in SQL, options reach the engine
  (`user_excluded`), real `invalid-csv` rejection with no rows, plus the 9
  client contract tests. No tracebacks in the engine log.

**Issues found and fixed during verification**

- `DatabaseSchemaTest` listed migrations exactly and failed once V3 existed,
  as intended; updated. The new foreign keys required deleting
  `reference_profile` first in every cleanup, now centralised in
  `TestDatabase.clear`.
- Two test bugs of mine: re-stubbing with `when(mock.call())` invoked the
  previous Mockito answer with null arguments (NPE), so I switched to
  `doThrow/doAnswer().when(mock)`; and I expected monitored columns to omit
  `excludedReason`, while the API (like `ModelResponse`) returns explicit
  `null`.
- Spotless kept re-wrapping test code between my edits; edits are now made
  by position after reading the formatted file.

**Known limitations**

- Profiling is synchronous, so a very large file holds an HTTP request and a
  server thread for up to the 5-minute read timeout.
- Failed registration attempts are not stored (logged only).

**Commit hash**

- `5ffdb81`

**Remaining work**

- 2.7 monitoring run lifecycle (`PENDING → RUNNING → SUCCEEDED/FAILED`) on a
  bounded executor.

**Open decisions**: license (MIT vs Apache-2.0); repository publication;
optional rename of the `io.driftwatch` package.

---

## 2026-11-21 — Milestone 2.5: analytics engine client

**Feature implemented**

- `analytics.AnalyticsClient`: `health()`, `profile(csv, filename, options)`,
  `drift(profileJson, csv, filename, config)` with Spring `RestClient` on the
  JDK HTTP client (HTTP/1.1, connect and read timeouts).
- Sealed `AnalyticsException` hierarchy: `AnalyticsRejectedException`
  (DriftWatch 4xx problem; keeps status, kind, detail, errors),
  `AnalyticsUnavailableException` (refused, timeout, 5xx),
  `AnalyticsProtocolException` (contract violations).
- `AnalyticsProperties` (`driftwatch.analytics.*`, validated at startup).
- `.github/workflows/engine-contract.yml`: installs the engine from its lock
  file, starts it, and runs `AnalyticsEngineContractTest`; triggered by
  changes to `analytics/` or `backend/`.

**Design decisions**

- **No retries in the client:** requests are idempotent (stateless
  engine), but re-sending large uploads doubles the load; the caller decides.
- **JSON trees, not Java copies of the engine's models:** the backend
  stores profiles and results as JSON and should not duplicate a data model
  it does not own.
- **`exchange()` instead of Spring's default status handling**, which would
  hide the engine's problem `type`.
- **Contract test against the real engine** rather than recorded fixtures:
  fixtures go stale without anyone noticing, while a real engine in CI fails
  as soon as either side changes the contract.

**Files changed**

- `backend/src/main/java/io/driftwatch/backend/analytics/*` (10 files)
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/io/driftwatch/backend/analytics/{AnalyticsClientTest,AnalyticsEngineContractTest}.java`
- `.github/workflows/engine-contract.yml`
- `backend/README.md`, `CONTRIBUTING.md`, `ARCHITECTURE.md` (layout, D6),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Temurin 21.0.13; engine 0.1.0.dev0 on Python 3.13.15)

- `./mvnw verify`: **101 passed, 9 skipped** (the contract tests, which
  need an engine). New: 15 stub-server tests covering multipart format (file
  part name, file name, `text/csv`; profile as an `application/json` file
  part; options and config), 32 MB upload, rejected 422/413 with kind and
  errors, `about:blank` and non-problem 4xx as protocol errors, 500 and HTML
  503 as unavailable, invalid JSON, missing field, **refused connection**
  (closed port), **read timeout** (300 ms limit, finishes in under 2 s).
- **Contract test against the real engine: 9 passed**: health, profile,
  options pass-through, profile → drift round trip (no drift; 1 sd shift
  flagged on `age` only), config pass-through, and the real `invalid-csv`,
  `schema-mismatch`, `invalid-profile` and `invalid-options` rejections. The
  engine's log shows 8 × 200 and 4 × 422, with no server errors.
- Replayed the `engine-contract` workflow steps locally: 9 passed.

**Issues found and fixed during verification**

- `MultipartBodyBuilder` failed with `NoClassDefFoundError:
  org/reactivestreams/Publisher`: it belongs to Spring's reactive stack.
  Replaced with `LinkedMultiValueMap` + `HttpEntity` parts (servlet-side
  `FormHttpMessageConverter`) instead of adding a reactive dependency.
- A test expected `multipart/form-data; boundary=`; Spring sends no space
  after `;`, which is equally valid (RFC 9110). The assertion now ignores the
  whitespace.

**Not verified**

- The `engine-contract` workflow has not run on GitHub (repository not
  published).

**Commit hash**

- `7a08deb`

**Remaining work**

- 2.6 reference registration workflow: `POST /api/v1/models/{id}/reference`
  → store → profile → persist schema and profile (V3 migration).

**Open decisions**: license (MIT vs Apache-2.0); repository publication;
optional rename of the `io.driftwatch` package.

---

## 2026-11-21 — Milestone 2.4: content-addressed dataset storage

**Scope decision:** the roadmap gives the public upload endpoints to 2.6
(reference registration) and 2.7 (monitoring runs). This task builds the
storage layer they will share, with no new endpoint, to avoid API surface
the plan does not call for.

**Feature implemented**

- `V2__create_dataset.sql`: `dataset` (UUID id, FK to `monitored_model` with
  `ON DELETE RESTRICT`, role check, lower-case hex SHA-256 check, positive
  size, optional original file name, `created_at`; index on
  `(model_id, created_at)`). Only columns this task fills.
- `ContentAddressedFileStore`: streaming SHA-256 and size limit, temporary
  file inside the root, read-only file, atomic move to
  `<root>/ab/cd/<hash>`, deduplication, strict hash validation.
- `Dataset` entity (UUIDv7, microsecond `createdAt`, model referenced by
  id), `DatasetRepository`, `DatasetService.store/forModel/open`,
  `DatasetProperties` (`driftwatch.datasets.*`, validated at startup).
- Test resources: each test context stores files in a fresh temporary
  directory, never in the working tree; `.gitignore` covers
  `backend/storage/` for local runs.

**Design decisions**

- **No transaction during the upload:** model check, then file copy, then
  a short insert transaction, so slow 100 MB uploads cannot exhaust the
  connection pool. The cost is possible unreferenced blobs if the insert
  fails; they are harmless and reusable (content addressing).
- **Path derived from the hash, not stored**, so the store root can move.
- **No CSV parsing in the backend:** the engine is the single
  implementation of the parsing rules.

**Files changed**

- `backend/src/main/resources/db/migration/V2__create_dataset.sql`
- `backend/src/main/java/io/driftwatch/backend/dataset/*` (10 files)
- `backend/src/main/resources/application.yml`, `backend/src/test/resources/application.properties`
- `backend/src/test/java/io/driftwatch/backend/dataset/{ContentAddressedFileStoreTest,DatasetServiceTest}.java`
- `backend/src/test/java/io/driftwatch/backend/{DatabaseSchemaTest,model/ModelApiTest}.java`
- `.gitignore`, `backend/README.md`, `ARCHITECTURE.md` (§6, D5),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Temurin 21.0.13, PostgreSQL 16.16 via Testcontainers)

- `./mvnw verify`: **86 passed** (34 new).
  - File store (16): hash and layout; deduplication; distinct content;
    limit boundary (exact OK, +1 rejected, nothing kept); endless stream cut
    off; empty rejected; 20 MB random content; read-only permissions;
    8 concurrent stores of the same content give one file and no temp
    leftovers; 4 invalid addresses including `../`; missing file.
  - Dataset service (13): store and read back; storage under the test temp
    directory; duplicate content gives two rows and one file; unknown model
    rejected before anything is written; oversized, empty and 4 invalid
    file names rejected without a row; name length 255/256; optional name;
    a traversal-looking name never becomes a path.
  - Schema (5 new): migration history V1 + V2; FK required; model with
    datasets cannot be deleted; role, hash format and size checks.
- The suite also passes with **reverse class order**
  (`-Dsurefire.runOrder=reversealphabetical`), checking the isolation fix.
- No `backend/storage/` created in the working tree.

**Issues found and fixed during verification**

- **Schema validation caught a type mismatch:** V2 first declared
  `sha256 CHAR(64)` while the entity maps `VARCHAR`; Hibernate's `validate`
  mode refused to start. Changed to `VARCHAR(64)` (PostgreSQL pads `CHAR`;
  the check already fixes the length). V2 had not been committed or applied
  to any persistent database, so editing it was safe.
- **Test isolation:** `DatasetServiceTest` committed a model that collided
  with `DatabaseSchemaTest` (same cached Spring context, so same database),
  an order-dependent failure. Every class now cleans up after itself, the
  schema test starts from empty tables inside its rolled-back transaction,
  and `ModelApiTest` deletes datasets before models.
- I first wrote a near-vacuous assertion in the path-traversal test;
  replaced it with a direct check that every stored file is named by its
  hash.

**Known limitations**

- Unreferenced blobs are not cleaned up yet.
- Each test context leaves a small temporary directory (about 4 KB) in the
  OS temp folder.

**Commit hash**

- `eebaef8`

**Remaining work**

- 2.5 analytics HTTP client with timeouts and error mapping.

**Open decisions**: license (MIT vs Apache-2.0); repository publication;
optional rename of the `io.driftwatch` package.

---

## 2026-11-21 — Milestone 2.3: model registry API

**Feature implemented**

- Package `io.driftwatch.backend.model`: `MonitoredModel` entity (UUIDv7 id
  via Hibernate's `@UuidGenerator(style = VERSION_7)`, `createdAt` truncated
  to microseconds), `MonitoredModelRepository`, `ModelService`,
  `ModelController`, records `CreateModelRequest` / `ModelResponse` /
  `ModelPage`.
- Endpoints: `POST /api/v1/models` (201 + `Location`), `GET /api/v1/models/{id}`,
  `GET /api/v1/models` (paginated, sorted by name then id for stable pages,
  size 1–100).
- Validation (Bean Validation, `spring-boot-starter-validation`): slug
  names, required task type, column names without blank/edge whitespace or
  line breaks, prediction ≠ target, unknown JSON fields rejected
  (`spring.jackson.deserialization.fail-on-unknown-properties`).
- `common.ApiExceptionHandler` + `ProblemTypes`: every error is RFC 9457 with
  `urn:driftwatch:problem:` types (same scheme as the engine); specific but
  safe messages (unknown field name, allowed enum values, expected parameter
  type); a catch-all 500 that logs details and returns none.

**Design decisions**

- **Uniqueness from the database constraint, not check-then-insert**, so
  concurrent requests cannot both succeed. `saveAndFlush` makes the
  violation surface inside the service, where it becomes a 409.
- **Reject, don't correct:** no trimming or lower-casing of names.
- **Microsecond `createdAt`:** PostgreSQL stores microseconds, so a
  nanosecond value returned by `POST` would differ from a later `GET`.

**Files changed**

- `backend/src/main/java/io/driftwatch/backend/model/*` (9 files),
  `backend/src/main/java/io/driftwatch/backend/common/{ApiExceptionHandler,ProblemTypes}.java`
- `backend/src/main/resources/application.yml`, `backend/pom.xml`
- `backend/src/test/java/io/driftwatch/backend/model/ModelApiTest.java`
- `backend/README.md` (API contract), `ARCHITECTURE.md` (§7),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Temurin 21.0.13, PostgreSQL 16.16 via Testcontainers)

- `./mvnw verify`: **52 passed** (32 new API tests over real HTTP): create
  (201, `Location`, UUIDv7, ISO timestamp); `GET` equals the `POST` body
  exactly; optional columns; columns with spaces; 404 and non-UUID 400;
  duplicate 409 with one row stored; **8 concurrent creates of the same name
  → exactly one 201, the rest 409, one row**; 7 invalid names; length limit
  100/101; all missing fields reported; 4 invalid column names;
  prediction = target; unknown enum with allowed values; unknown field;
  malformed JSON without parser details; 415; sorted pagination; empty list;
  out-of-range and non-numeric paging.
- **Mutation check:** with `fail-on-unknown-properties: false`, the
  unknown-field test fails; configuration restored.
- Live run with `./mvnw spring-boot:test-run` and `curl`: create, duplicate
  409, list. No containers left running afterwards.

**Issues found and fixed during verification**

- Two tests failed on my error messages, "an UUID" and "a int", produced by
  a naive English-article heuristic. Replaced with explicit descriptions
  ("a UUID", "an integer").
- I had put a DTO-specific field-name mapping inside the shared exception
  handler; removed it and gave the cross-field check a self-describing
  property name (`predictionColumnDifferentFromTarget`) instead.
- Process: `set -e` did not stop a zsh script when an embedded Python edit
  failed. Nothing was committed by that script; edit scripts now exit
  explicitly on failure.

**Known limitation**

- The microsecond-precision test can only catch a regression on Linux: on
  macOS `Instant.now()` already has microsecond resolution. CI runs on Linux.

**Commit hash**

- `a62547a`

**Remaining work**

- 2.4 dataset upload, SHA-256 content-addressed file store, size limits.

**Open decisions**: license (MIT vs Apache-2.0); repository publication;
optional rename of the `io.driftwatch` package.

---

## 2026-11-21 — Milestone 2.2: PostgreSQL, Flyway and Testcontainers

**Feature implemented**

- Dependencies (versions managed by Spring Boot 4.2.0): `spring-boot-starter-data-jpa`
  (Hibernate 7.4), `spring-boot-starter-flyway` (Flyway 12.11) +
  `flyway-database-postgresql`, PostgreSQL JDBC 42.7.14; for tests,
  `spring-boot-testcontainers` and Testcontainers 2.0.5
  (`testcontainers-postgresql`, `testcontainers-junit-jupiter`).
- `V1__create_monitored_model.sql`: the only table the next task needs.
  UUID primary key (application-generated), unique and non-blank `name`,
  `task_type` restricted by a check constraint, optional target/prediction
  columns, `created_at` default.
- Configuration: `ddl-auto: validate`, `open-in-view: false`,
  `validate-on-migrate: true`; datasource from `SPRING_DATASOURCE_*`, with no
  password in the repository; readiness group includes `db`, liveness does
  not.
- `TestcontainersConfiguration` (`postgres:16.16-alpine`, exact tag, same
  digest as `16-alpine` at the time) and `TestDriftWatchApplication` for
  `./mvnw spring-boot:test-run` without a local PostgreSQL.

**Decisions**

- **One table, not the whole data model.** Creating all five tables from
  ARCHITECTURE.md now would put unused tables in the schema. Each feature
  adds its own migration.
- **Readiness includes the database, liveness does not**, so a database
  outage stops traffic but does not trigger restart loops.

**Finding: third-party Maven repositories**

- The first build logged HTTP 401 warnings from two private Redgate
  repositories. They are declared in `flyway-parent-12.11.0.pom`, and Maven
  queries every repository a dependency's POM declares. Nothing was
  downloaded from them, but builds should not depend on third-party
  repositories. `pom.xml` now redeclares both ids as disabled. Verified with
  an **empty local repository**: all 831 downloads came from Maven Central,
  with no warnings.

**Files changed**

- `backend/pom.xml`, `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V1__create_monitored_model.sql`
- `backend/src/test/java/io/driftwatch/backend/{TestcontainersConfiguration,TestDriftWatchApplication,DatabaseSchemaTest,ReadinessProbeTest,ActuatorEndpointsTest}.java`
- `backend/README.md`, `ARCHITECTURE.md` (§6), `CONTRIBUTING.md`,
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Temurin 21.0.13, Docker 29.9.2, PostgreSQL 16.16)

- `./mvnw verify`: **20 passed** (10 new). Schema: Flyway history at V1;
  server version 16.x; exact columns, types and nullability; valid insert
  with timestamp; duplicate name, unknown task type, blank name, missing
  name and 101-character name each rejected by the intended constraint.
  Readiness: with the database stopped mid-test, readiness and overall
  health return 503 while liveness stays 200. The 10 existing endpoint tests
  now run against the container.
- **Mutation check:** with `db` removed from the readiness group, the
  readiness test fails (expected 503, got 200); configuration restored.
- `./mvnw spring-boot:test-run`: started a throwaway PostgreSQL, applied V1,
  readiness 200.
- No containers left running afterwards (Testcontainers cleans up).

**Environment note**

- The Docker daemon was not running at the start; I started Docker Desktop
  (`open -a Docker`) because the approved plan requires Testcontainers.

**Commit hash**

- `139fd24`

**Remaining work**

- 2.3 model registry API (`/api/v1/models`) with validation and Problem
  Details errors, using the `monitored_model` table.

**Open decisions**: license (MIT vs Apache-2.0); repository publication;
optional rename of the `io.driftwatch` package.

---

## 2026-11-21 — Milestone 2.1: backend scaffold

**Feature implemented**

- Maven project `backend/`: Spring Boot **4.2.0** (newest GA on Maven
  Central; 4.1.3 was the newest patch of the previous line), Java 21 via
  `java.version` (compiler `release` 21), `spring-boot-starter-webmvc` and
  `-actuator`.
- Maven wrapper 3.3.4, script-only (no binary jar committed), pinned to
  Maven **3.10.1** with `distributionSha256Sum`. Before pinning, the
  downloaded zip's SHA-512 was checked against Maven Central's published
  checksum; then the wrapper cache was cleared and a fresh download was
  verified against the pinned SHA-256.
- Actuator: only `health` and `info` are exposed; health hides details and
  has liveness/readiness probes; info shows build info only (no Java/OS
  details, no misleading fixed 1980 build time).
- Spotless (google-java-format 2.0.0, unused imports, sortPom) bound to
  `verify`.
- Mockito is loaded as a `-javaagent` through Surefire, removing the JDK
  warning about dynamically loaded agents (which future JDKs will block).
- `.github/workflows/backend.yml`: `./mvnw verify` on Temurin 21,
  `ubuntu-24.04`, read-only token, no persisted credentials, SHA-pinned
  `actions/checkout` v7.0.1 and `actions/setup-java` v6.3.0 (lightweight
  tag; commit SHA from `git ls-remote`).

**Files changed**

- `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`,
  `backend/.mvn/wrapper/maven-wrapper.properties`
- `backend/src/main/java/io/driftwatch/backend/DriftWatchApplication.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/io/driftwatch/backend/ActuatorEndpointsTest.java`
- `.github/workflows/backend.yml`
- `.gitattributes`: LF everywhere, CRLF for `*.cmd`/`*.bat` (Windows needs
  it; `git diff --check` had flagged every line of `mvnw.cmd` as trailing
  whitespace because of the CR), LF for `mvnw` and `*.sh`
- `backend/README.md`, `README.md`, `ARCHITECTURE.md`,
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Temurin 21.0.13, Maven 3.10.1 via the wrapper)

- `./mvnw verify`: **10 tests passed**, running the real application on a
  random port over HTTP: health UP without details; liveness and readiness
  probes; info with artifact and version but no time/Java/OS; 404 for `env`,
  `beans`, `configprops`, `heapdump`, `loggers`, `mappings`.
- Spotless check passes, and a deliberately misformatted file makes
  `spotless:check` fail; the file was then restored.
- Replayed the CI command with an empty local Maven repository: build
  success.
- Ran the packaged jar and called every endpoint with `curl` (output
  matched the tests).
- Workflow structure checked with Ruby's YAML parser (triggers, path
  filters, permissions, SHA pins, credentials, Java version).

**Not verified**

- Neither CI workflow has run on GitHub yet (repository not published).
- `actionlint`/`zizmor` still not run (blocked earlier by the session's
  permission policy).

**Commit hash**

- `0e04631`

**Remaining work**

- 2.2 PostgreSQL + Flyway baseline migration; Testcontainers setup. This
  needs a running Docker daemon for the integration tests.

**Open decisions**: license (MIT vs Apache-2.0); repository publication.

---

## 2026-11-21 — Milestone 1.10: CI for the analytics engine (Milestone 1 complete)

**Feature implemented**

- `.github/workflows/analytics.yml`: on pushes to `main` and pull requests
  touching `analytics/`, plus manual runs. Matrix: Python 3.12, 3.13, 3.14
  on `ubuntu-24.04`. Steps: install from the lock file, `pip check`,
  `ruff format --check`, `ruff check`, `mypy`, `pytest`.
- Hardening: read-only `contents` permission, `persist-credentials: false`,
  actions pinned to commit SHAs (`actions/checkout` v7.0.1,
  `actions/setup-python` v7.1.0; lightweight tags, so the tag SHA is the
  commit SHA, checked with `git ls-remote`), a 15-minute timeout and
  cancellation of superseded runs.
- `analytics/requirements-dev.lock`: universal lock of all runtime and dev
  dependencies (`uv pip compile --universal`), closing the reproducibility
  gap noted in 1.1. CI installs exactly the versions tested locally.

**Files changed**

- `.github/workflows/analytics.yml`, `analytics/requirements-dev.lock`
- `analytics/README.md` (setup, lock, CI), `CONTRIBUTING.md`, `README.md`
  (status), `ARCHITECTURE.md` (layout), `DEVELOPMENT_ROADMAP.md`,
  `DEVELOPMENT_LOG.md`. No changelog entry: contributor tooling, not a
  user-facing change.

**Tests executed**

- Replayed the CI steps exactly in fresh virtualenvs for **Python 3.12.15,
  3.13.15 and 3.14.10**: lock install, `pip check` clean, format, lint and
  strict mypy clean, **317 passed** on each. This is the first verification
  on 3.12 (the declared minimum) and 3.14.
- Workflow structure: parsed with Ruby's built-in YAML and checked triggers,
  identical path filters, permissions, matrix, SHA pins, credentials,
  working directory and timeout.

**Not verified**

- The workflow has **not run on GitHub**: there is no remote, and pushing
  needs maintainer approval. The local replay was on macOS/arm64, while CI
  uses Linux/x86-64 wheels.
- `actionlint` and `zizmor` were **not run**. Running them through `uvx`
  (download from PyPI and execute) was blocked by this session's permission
  policy, and I did not work around it. They can be run once they are
  approved or installed.

**Commit hash**

- `ae827f6` (workflow and lock file) + `5f6a08a` (README status correction)

**Remaining work**

- Milestone 2: Spring Boot backend, starting with 2.1 (Maven scaffold,
  Java 21, Spotless, Actuator health, CI job).
- First CI run on GitHub after the repository is pushed.

**Open decisions**: license (MIT vs Apache-2.0); when and where to push
the repository.

---

## 2026-11-21 — Milestone 1.9: no-drift controls and real-data tests

**Feature implemented** (tests only, no behaviour change)

- `tests/drift/test_no_drift_controls.py`: no-drift false-alarm checks on
  9 distribution shapes chosen to stress the noise floor's multinomial
  model (skewed, heavy-tailed, bimodal, tied, zero-inflated, rounded, 95/5,
  rare-tail and 60-category). Bound: at most 10 alarms in 120 trials.
- `tests/test_real_data.py`: breast cancer, diabetes (unscaled), iris and
  wine from scikit-learn's bundled files (offline). Random-halves controls;
  biased splits with an independent full-data KS test and Cohen's d as
  ground truth; type inference on raw real columns; missing values on real
  data; one full HTTP round trip on real data.
- scikit-learn 1.9 added as a **dev** dependency (tests only); it becomes a
  runtime dependency with performance metrics in Milestone 3. A mypy
  override covers only `sklearn.*`, which has no type information, and the
  tests load data through a typed helper that checks with `isinstance`.

**Method:** every bound was set after measuring, and the measurements are
written next to each assertion.

**Files changed**

- `analytics/tests/drift/test_no_drift_controls.py`, `analytics/tests/test_real_data.py`
- `analytics/pyproject.toml`, `analytics/README.md`
- `ML_MONITORING_DESIGN.md` (new §10.1 results), `DEVELOPMENT_ROADMAP.md`,
  `DEVELOPMENT_LOG.md`. No changelog entry: no user-facing change.

**Tests executed** (Python 3.13.15)

- `pytest`: 317 passed (21 new) in about 10 s.
- Exploration before writing assertions (300 trials per cell): stress shapes
  0–3.3% false alarms at 50 rows and 0–1.3% at 200 and 1,000; breast cancer
  halves 0/1,200; diabetes halves 6/440; wine halves 20/840.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Findings**

- On the benign-vs-malignant split, `mean fractal dimension` is flagged
  (PSI 0.205, just over 0.2) although its mean is nearly unchanged
  (d = 0.03). Cohen's d alone is the wrong ground truth: PSI also reacts to
  changes in spread and shape. An independent full-data KS test confirms a
  modest real difference (p = 0.02), so the tests use KS evidence instead.
- The no-drift rate is highest (3.3%) for very skewed data at 50 rows, in
  line with the documented ~2% small-batch limitation.

**Issues found and fixed during verification**

- I first wrote a confusing seed expression that mentioned `hash()`, which
  is not stable across processes; replaced with fixed per-case seeds before
  running.
- Removed a fixture that claimed to check the datasets were offline but only
  inspected a module name; the docstring states the fact instead.
- mypy: scikit-learn has no type information (handled as above).

**Commit hash**

- `1c12c46`

**Remaining work**

- 1.10 GitHub Actions workflow for `analytics` (lint, type check, tests),
  which completes Milestone 1.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.8: `/v1/profile` and `/v1/drift` endpoints

**Feature implemented**

- `POST /v1/profile`: multipart `file` (CSV) + optional JSON `options`
  (overrides, exclude, profile config) → `{profile, issues}`.
- `POST /v1/drift`: multipart `profile` (JSON **file**) + `current` (CSV) +
  optional JSON `config` → engine versions, full config echo, row count,
  dataset summary (`drift_share`, `dataset_drift` at
  `DriftConfig.dataset_drift_share`, default 0.5), per-feature results,
  issues and warnings (engine version mismatch).
- RFC 9457 problem details for every error, with stable `type` URNs
  (`invalid-csv`, `empty-dataset`, `invalid-options`, `unprofilable-dataset`,
  `invalid-profile`, `invalid-config`, `schema-mismatch`, `upload-too-large`,
  `invalid-request`). Error lists are capped at 20 and never echo inputs.
- Upload limits: per-file `DRIFTWATCH_MAX_UPLOAD_BYTES` (default 100 MB), plus
  middleware that rejects an oversized `Content-Length` before the body is
  read. `Settings` is read from the environment and injectable in tests.
- Endpoints are synchronous so CPU-bound statistics run in FastAPI's thread
  pool. Added the `python-multipart` dependency.

**Design decision**

- The profile is uploaded as a file part. Checking Starlette's source showed
  ordinary form fields are capped at 1 MB. A profile stores up to 10,000
  sample values per numeric feature (about 180 KB each at the full sample);
  the 3,000-row, 3-numeric-feature test dataset already gives a 175 KB
  profile, and wider datasets would pass 1 MB.

**Files changed**

- `analytics/src/driftwatch_analytics/api/{errors,uploads,models,profile,drift}.py`
- `analytics/src/driftwatch_analytics/{main,settings}.py`
- `analytics/src/driftwatch_analytics/drift/evaluation.py` (`dataset_drift_share`)
- `analytics/tests/test_api.py`, `analytics/pyproject.toml`
- `analytics/README.md` (API contract), `ARCHITECTURE.md` (§7),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 296 passed (35 new API tests). Profile success, options,
  issues with severity; 5 bad CSVs, 5 invalid options, malformed options
  JSON, unprofilable column, missing part; drift on unchanged data (exact
  summary), shifted features plus a missing-rate flag (exact attribution and
  summary), config echo, insufficient data, extra columns, schema mismatch,
  4 invalid and 1 tampered profile, invalid config, invalid current CSV,
  engine version warning, byte-identical repeated responses; per-file and
  `Content-Length` limits; settings from the environment; OpenAPI paths.
- Live check with `uvicorn` and `curl`: profile → drift round trip
  (1 sd shift on `age` flagged, PSI 0.88, nothing else flagged); 4.2 MB upload
  with a 2 MB limit → 413; malformed CSV → 422 problem response; no errors in
  the server log.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Issues found and fixed during verification**

- A test expected a schema-violation warning on an *inferred* column, which
  cannot happen: numeric inference needs ≥ 95% parseable values, while a
  violation needs > 5% invalid. The test now forces the type with an
  override, which is the only way a reference column can violate its schema.
- During drafting the profile endpoint briefly had two duplicate CSV helpers,
  one untyped; replaced with one typed `read_csv_upload` before any commit.

**Known limitations**

- A chunked request without `Content-Length` is only bounded per file after
  it has been received (spooled to disk). The backend will enforce its own
  upload limits (Milestone 2).
- No authentication; the engine is meant to be reachable only by the backend.

**Commit hash**

- `11a7aed`

**Remaining work**

- 1.9 false-positive-rate and real-data tests (scikit-learn bundled datasets).
- 1.10 GitHub Actions workflow for `analytics`.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.7c: sample-size-aware noise floor

**Decision (approved by maintainer):** noise floor rather than a fixed
minimum batch size. Options and simulations were presented first; the fixed
minimum was rejected because it gives no verdict on small batches even when
drift is obvious.

**Feature implemented**

- `drift.noise.noise_floor`: seeded parametric bootstrap. Draws 500 pairs of
  reference and current histograms from the reference proportions at the
  real sample sizes and returns the 99th percentile of the statistic.
- PSI and JS now flag only above `effective_threshold = max(threshold,
  noise_floor)`. Results gained `noise_floor` and `effective_threshold`.
  `NoiseFloorConfig` (quantile, simulations, seed) is part of both method
  configs; `noise_floor=None` restores the fixed threshold.
- Vectorised PSI helper (`_psi_rows`) and JS helper (`_js_rows`) so the
  simulations are fast (~1 ms per feature).
- `scripts/sample_size_policy.py`: reproduces the policy table using the
  project's own drift functions; `scripts/` added to the mypy targets.

**Files changed**

- `analytics/src/driftwatch_analytics/drift/{noise,models,numeric,categorical,evaluation,__init__}.py`
- `analytics/tests/drift/{test_noise_floor,test_numeric}.py`
- `analytics/scripts/sample_size_policy.py`, `analytics/pyproject.toml`
- `analytics/README.md`, `ML_MONITORING_DESIGN.md` (new §3.4; §3.1, §4.1,
  §7 updated), `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 261 passed (22 new). The floor shrinks as the batch grows;
  large batches use the fixed threshold; small batches raise it;
  determinism; constant reference; disabling the floor. **False alarms over
  300 seeded no-drift trials at 30/50/100 rows**: measured PSI 2, 4 and 3
  out of 300 and JS 5, 3 and 3 out of 300 (bound: ≤ 9, the 99.9% binomial
  limit for a 1% rate); the fixed threshold flags over 30% at 30–50 rows.
  **Power**: a 1 sd shift is detected in ≥ 70% / 95% / 99% of trials at
  50 / 100 / 500 rows; a strong category shift in ≥ 90% at 50 rows; a
  0.5 sd shift at 2,000 rows has the same verdict with and without the floor.
- All 239 existing tests passed unchanged with the floor enabled.
- `scripts/sample_size_policy.py` (200 trials, fresh reference per trial):
  no-drift false alarms 0–2% at all sizes vs. 44–72% fixed at 30–50 rows;
  table copied into the design doc §3.4.
- `ruff format --check .`, `ruff check .`, `mypy` (strict, now including
  `scripts/`): clean.

**Limitations recorded**

- Slightly above 1% (about 2%) at 30–50 rows, because the multinomial
  model ignores noise in the estimated bin edges.
- Small batches can only reveal large changes (0.5 sd shift caught 28% of
  the time at 50 rows).

**Commit hash**

- `62b0c59`

**Remaining work**

- 1.8 `/v1/profile` and `/v1/drift` endpoints with request validation and
  API tests.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.7b: per-feature evaluation with data-quality flags

**Feature implemented**

- `drift.evaluate_batch(profile, report, config)`: one `FeatureDriftResult`
  per monitored feature, in schema order, combining validation, profile and
  drift methods.
- `FeatureStatus` (`evaluated`, `insufficient_data`) and `QualityFlag`
  (`schema_violation`, `missing_rate_change` > 5 points, `constant_in_reference`,
  `unseen_categories`). `drift_detected` covers distribution drift only.
- `DriftConfig` groups the numeric and categorical configs,
  `min_valid_count` (30, a placeholder for 1.7c) and `max_missing_rate_change`.
- Rejects invalid reports and reports built from a different schema.

**Files changed**

- `analytics/src/driftwatch_analytics/drift/{evaluation,__init__}.py`
- `analytics/tests/drift/test_evaluation.py`
- `analytics/README.md`, `ML_MONITORING_DESIGN.md` (§7),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 239 passed (16 new), all end to end through CSV → schema →
  profile → validation → evaluation: clean batch; drift attributed to exactly
  the shifted features; 20% injected missing values flagged without drift;
  4% not flagged; 10% unparseable values flagged; unseen category flagged;
  insufficient data with quality flags still reported; configurable minimum;
  constant reference; boolean feature; excluded columns; invalid and
  mismatched reports rejected; JSON round trip; determinism.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean (one
  mypy error from an untyped `**kwargs` construction fixed before commit).

**Commit hash**

- `ef2cd9c`

**Remaining work**

- 1.7c minimum-sample-size policy; awaiting maintainer decision.

**Open decisions**: sample-size policy (1.7c); license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.7a: normalised Wasserstein distance

Task 1.7 is split into 1.7a (this), 1.7b (per-feature evaluation and
data-quality flags) and 1.7c (minimum-sample-size policy), because 1.7c is a
statistical policy decision that needs maintainer approval.

**Feature implemented**

- `NumericDriftResult.wasserstein_normalized`: Wasserstein-1 distance between
  the profile sample and the current values, divided by the reference sd.
  `None` for a constant reference. Reported only.

**Files changed**

- `4163004`: `analytics/src/driftwatch_analytics/drift/{models,numeric}.py`,
  `analytics/tests/drift/test_numeric.py`, `ML_MONITORING_DESIGN.md` (§3.3),
  `analytics/README.md` (whitespace only, see below)
- Follow-up docs commit: `analytics/README.md`, `CHANGELOG.md`,
  `DEVELOPMENT_ROADMAP.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 223 passed (6 new): equals the shift in reference sds for shifts
  0 to 2; unchanged under rescaling (×1000); matches SciPy; `None` for a
  constant reference.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Process issue (my mistake)**

- The documentation script failed partway because `ruff format` also
  reformats Python code blocks inside `analytics/README.md`, so an exact-text
  edit did not match. The shell steps were not chained to stop on failure,
  so `4163004` was committed with the code, tests and design doc but without
  the README, changelog, roadmap and log updates; it also contains ruff's
  whitespace-only re-alignment of README code comments. Existing commits are
  not amended, so the missing documentation is added in a separate `docs:`
  commit. Fix going forward: commit scripts run with `set -e`.

**Commit hash**

- `4163004` (feature) + `2144b9c` (docs follow-up)

**Remaining work**

- 1.7b per-feature evaluation with data-quality flags.
- 1.7c minimum-sample-size policy.

**Open decisions**: license (MIT vs Apache-2.0).

---

## 2026-11-21 — Milestone 1.6: categorical drift (Jensen–Shannon and chi-square)

**Feature implemented**

- `drift.categorical_drift(profile, labels, config)`: aligns reference and
  current counts on ordered buckets, computes Jensen–Shannon distance
  (base 2; sets `drift_detected`, threshold 0.1) and a chi-square test of
  homogeneity (reported only). Returns `CategoricalDriftResult` with
  per-bucket proportions, unseen categories (capped list plus total count),
  `reference_truncated`, pooled categories and warnings.
- `drift.pool_rare_buckets`: deterministic pooling so every expected count
  is ≥ 5, using `C_j ≥ 5·N/min(R)` for 2 × k tables.
- Refactor: `profiling.category_labels` is now the single conversion of
  parsed values to labels (booleans to `"true"`/`"false"`), used by the
  profile builder and by drift detection. Existing tests passed unchanged
  after the refactor.

**Files changed**

- `analytics/src/driftwatch_analytics/drift/{__init__,models,categorical}.py`
- `analytics/src/driftwatch_analytics/profiling/{__init__,builder,categories}.py`
- `analytics/tests/drift/test_categorical.py`
- `analytics/README.md`, `ML_MONITORING_DESIGN.md` (§4.1, §4.2),
  `DEVELOPMENT_ROADMAP.md`, `CHANGELOG.md`, `DEVELOPMENT_LOG.md`

**Tests executed** (Python 3.13.15)

- `pytest`: 217 passed (25 new). JS against an independent implementation of
  the definition; chi-square against SciPy on the same table; identical
  distributions (JS 0, p = 1); disjoint categories (JS 1); no drift not
  flagged; proportion shift detected; JS increasing with shift size;
  large-sample caveat (chi-square significant, JS not); unseen categories
  in complete vs. truncated profiles (truncated: no false drift on identical
  data); capped unseen report; three pooling cases; skipped test on tiny
  batches; **200 no-drift trials**: 0 JS false alarms, chi-square
  rejections within the expected binomial range; boolean feature end to end
  (validate → labels → drift); JSON round trip; determinism; invalid input
  and config.
- `ruff format --check .`, `ruff check .`, `mypy` (strict): clean.

**Issues found and fixed during verification**

- Three pooling tests were wrong; the code was right. In each, the rare
  categories combined still had expected counts below 5 (e.g. 4 rare rows
  against a 5,000-row reference gives an expected count of 3.3), so
  absorbing another category was correct. The tests now use tables whose
  intended outcome is what the statistics require, with the arithmetic in
  comments.

**Finding that affects the design**

- Simulated JS false-alarm rates under no drift (4 categories): 85% at 30
  rows per side, 71% at 50, 14% at 200, under 1% from about 500. Recorded in
  §4.1 alongside the PSI finding from 1.5; task 1.7 must address minimum
  sample size for both methods.

**Commit hash**

- `86d70a8`

**Remaining work**

- 1.7 Wasserstein, data-quality flags and a minimum-sample-size policy that
  keeps no-drift false alarms low.

**Open decisions**: license (MIT vs Apache-2.0).

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

- `231136a`

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

- `b6211ff`

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

- `c4dc3bb`

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

- `2056842`

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

- `35c5b9a`

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

- `8caa76b`

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

- `370c809`

**Remaining work**

- Milestone 1, task 1.1: scaffold the `analytics` Python project.

**Open decisions (need maintainer approval)**

1. **License** — MIT (simple, permissive) or Apache-2.0 (permissive, with an
   explicit patent grant). No license file is added until this is decided.
2. **Git author name** — the global `user.name` is not set; Git derived a name
   from the system account for this commit. Set it with
   `git config --global user.name "Your Name"` for future commits.
3. ~~**Build order**~~ — confirmed by maintainer: analytics first.
