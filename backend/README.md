# backend — platform API

**Status:** Milestone 2 in progress. Implemented: project scaffold with
health and build-info endpoints (2.1). No domain API yet.

Spring Boot application that will expose the public REST API, store models,
datasets, reference profiles and monitoring results in PostgreSQL, and call
the [analytics](../analytics/) engine to do the statistics. It is the only
component that owns persistent state. See
[ARCHITECTURE.md](../ARCHITECTURE.md).

## Stack

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 (LTS) | `maven.compiler.release=21`: Java 21 bytecode and API even if a newer JDK runs Maven |
| Spring Boot | 4.2.0 | Newest GA release when the project was scaffolded |
| Maven | 3.10.1 via the wrapper | `./mvnw` downloads it and checks its SHA-256 |
| Formatting | Spotless 3.12.0 + google-java-format 2.0.0, sortPom | Bound to `verify`, so CI fails on unformatted code |

## Local setup

Requires a Java 21 JDK; Maven itself is fetched by the wrapper.

```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; any JDK 21 works
./mvnw verify                                       # compile, test, format check
```

## Run

```bash
./mvnw spring-boot:run
curl localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

## Checks

CI ([`.github/workflows/backend.yml`](../.github/workflows/backend.yml)) runs
`./mvnw verify` on Java 21 for every push to `main` and every pull request
that touches `backend/`.

```bash
./mvnw verify            # everything CI runs
./mvnw spotless:apply    # fix formatting
./mvnw test              # tests only
```

## Endpoints

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/actuator/health` | `200 {"status":"UP", "groups":["liveness","readiness"]}` |
| `GET` | `/actuator/health/liveness`, `/actuator/health/readiness` | `200 {"status":"UP"}` (container probes) |
| `GET` | `/actuator/info` | Build artifact, group, name and version |

**Exposure policy** (see `application.yml`): only `health` and `info` are
exposed over HTTP. Endpoints such as `env`, `beans`, `configprops`,
`heapdump`, `loggers` and `mappings` can leak configuration or memory
contents, so they stay unexposed; a test checks that each returns 404.
Health hides component details, and info omits the server's Java and OS
details. The build time is left out of build info, because the Spring Boot
parent fixes timestamps for reproducible builds and the value would always
read 1980.

## Planned layout

```text
backend/
├── pom.xml
└── src/
    ├── main/java/io/driftwatch/backend/
    │   ├── model/        monitored model registry
    │   ├── dataset/      upload, hashing, file store, schema checks
    │   ├── run/          monitoring run lifecycle and results
    │   ├── analytics/    HTTP client for the analytics engine
    │   └── common/       error handling (Problem Details), config
    ├── main/resources/db/migration/   Flyway migrations
    └── test/java/...                  unit + Testcontainers integration tests
```

## Dependencies

| Dependency | Purpose | Status |
|------------|---------|--------|
| spring-boot-starter-webmvc | REST API | added |
| spring-boot-starter-actuator | Health and info endpoints | added |
| spring-boot-starter-test (JUnit 5, AssertJ, Mockito) | Tests | added |
| spring-boot-starter-validation | Bean Validation at the API boundary | 2.3 |
| spring-boot-starter-data-jpa, postgresql | Persistence | 2.2 |
| flyway-core, flyway-database-postgresql | Schema migrations | 2.2 |
| testcontainers (postgresql, junit-jupiter) | Integration tests against real PostgreSQL | 2.2 |

Spring Boot 4 split its starters into smaller modules (for example
`spring-boot-starter-webmvc` instead of `spring-boot-starter-web`), so names
can differ from Spring Boot 3 tutorials.

The base package `io.driftwatch` follows the reverse-domain convention but
does not correspond to an owned domain; it can be renamed before the first
release.
