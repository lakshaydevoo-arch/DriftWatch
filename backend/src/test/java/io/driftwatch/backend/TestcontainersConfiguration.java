package io.driftwatch.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL in a container, wired into the application through {@link ServiceConnection}
 * (no datasource properties needed).
 *
 * <p>Tests that import this configuration share one Spring context, and therefore one container,
 * thanks to Spring's test context cache.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /** Pinned exact version (matches production's PostgreSQL 16; see ARCHITECTURE.md). */
  static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16.16-alpine");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(POSTGRES_IMAGE);
  }
}
