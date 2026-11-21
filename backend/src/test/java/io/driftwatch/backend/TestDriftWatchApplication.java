package io.driftwatch.backend;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throwaway PostgreSQL container, so no database has to be
 * installed: {@code ./mvnw spring-boot:test-run}. Data is lost when the application stops.
 */
public class TestDriftWatchApplication {

  public static void main(String[] args) {
    SpringApplication.from(DriftWatchApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
