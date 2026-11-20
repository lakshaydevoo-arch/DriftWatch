# backend — platform API

**Status:** not implemented (Milestone 2).

Spring Boot application that exposes the public REST API, stores models,
datasets, reference profiles and monitoring results in PostgreSQL, and calls
the [analytics](../analytics/) engine to do the statistics. It is the only
component that owns persistent state. See
[ARCHITECTURE.md](../ARCHITECTURE.md).

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

The base package name `io.driftwatch` is a placeholder and can be changed
before the scaffold is created.

## Planned dependencies

| Dependency | Purpose |
|------------|---------|
| spring-boot-starter-web | REST API |
| spring-boot-starter-validation | Bean Validation at the API boundary |
| spring-boot-starter-data-jpa | Persistence |
| spring-boot-starter-actuator | Health endpoints |
| postgresql (JDBC driver) | Database driver |
| flyway-core, flyway-database-postgresql | Schema migrations |
| spring-boot-starter-test (JUnit 5, AssertJ, Mockito) | Unit tests |
| testcontainers (postgresql, junit-jupiter) | Integration tests against real PostgreSQL |
| spotless-maven-plugin (google-java-format) | Formatting check |

Java 21, Maven Wrapper. The latest stable Spring Boot release compatible with
Java 21 is selected and pinned when the module is scaffolded.
