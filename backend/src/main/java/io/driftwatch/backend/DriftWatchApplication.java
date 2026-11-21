package io.driftwatch.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the DriftWatch backend. */
@SpringBootApplication
public class DriftWatchApplication {

  public static void main(String[] args) {
    SpringApplication.run(DriftWatchApplication.class, args);
  }
}
