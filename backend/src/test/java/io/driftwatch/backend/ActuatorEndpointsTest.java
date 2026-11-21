package io.driftwatch.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Starts the real application on a random port and checks the operational endpoints over HTTP, the
 * way an orchestrator or load balancer would call them.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ActuatorEndpointsTest {

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsUpWithoutComponentDetails() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
    // show-details: never -> no component names or details (e.g. database hosts later).
    assertThat(response.body()).doesNotContain("components").doesNotContain("details");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  void probesAreAvailableForOrchestrators(String path) throws Exception {
    HttpResponse<String> response = get(path);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
  }

  @Test
  void infoReportsBuildVersionButNotServerDetails() throws Exception {
    HttpResponse<String> response = get("/actuator/info");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"artifact\":\"driftwatch-backend\"")
        .contains("\"version\":\"0.1.0-SNAPSHOT\"")
        .doesNotContain("\"time\"")
        .doesNotContain("\"java\"")
        .doesNotContain("\"os\"");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator/env",
        "/actuator/beans",
        "/actuator/configprops",
        "/actuator/heapdump",
        "/actuator/loggers",
        "/actuator/mappings"
      })
  void sensitiveActuatorEndpointsAreNotExposed(String path) throws Exception {
    assertThat(get(path).statusCode()).isEqualTo(404);
  }
}
