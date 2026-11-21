package io.driftwatch.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stops the database while the application is running: the service must report "not ready" (so load
 * balancers stop sending traffic) but stay "alive" (so the orchestrator does not restart it in a
 * loop during a database outage).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
// Fail fast instead of waiting for Hikari's default 30 s connection timeout.
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=2000")
// This test stops the shared container, so its context must not be reused.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessProbeTest {

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @Autowired private PostgreSQLContainer postgres;

  private int status(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void databaseOutageMakesTheServiceUnreadyButNotDead() throws Exception {
    assertThat(status("/actuator/health/readiness")).isEqualTo(200);
    assertThat(status("/actuator/health/liveness")).isEqualTo(200);

    postgres.stop();

    assertThat(status("/actuator/health/readiness")).isEqualTo(503);
    assertThat(status("/actuator/health/liveness")).isEqualTo(200);
    assertThat(status("/actuator/health")).isEqualTo(503);
  }
}
