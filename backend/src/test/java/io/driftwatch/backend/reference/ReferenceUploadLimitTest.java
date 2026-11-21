package io.driftwatch.backend.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.driftwatch.backend.Multipart;
import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.model.ModelService;
import io.driftwatch.backend.model.TaskType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both upload limits, with small values so they can be crossed cheaply: the file store's limit (1
 * KB) and Spring's multipart limit (8 KB, set higher here only to reach the store's check).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
    properties = {
      "driftwatch.datasets.max-upload-size=1KB",
      "spring.servlet.multipart.max-file-size=8KB",
      "spring.servlet.multipart.max-request-size=16KB"
    })
class ReferenceUploadLimitTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ModelService models;
  @MockitoBean private AnalyticsClient analytics;

  private UUID modelId;

  @BeforeEach
  void setUp() {
    TestDatabase.clear(jdbc);
    modelId = models.create("m", TaskType.REGRESSION, null, null).getId();
  }

  @AfterEach
  void tearDown() {
    TestDatabase.clear(jdbc);
  }

  private HttpResponse<String> upload(int bytes) throws Exception {
    byte[] content = new byte[bytes];
    Arrays.fill(content, (byte) 'x');
    Multipart body = new Multipart().file("file", "big.csv", "text/csv", content);
    return http.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/models/" + modelId + "/reference"))
            .header("Content-Type", body.contentType())
            .POST(body.publisher())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertTooLarge(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(json.readTree(response.body()).get("type").asString())
        .isEqualTo("urn:driftwatch:problem:upload-too-large");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dataset", Integer.class)).isZero();
    verify(analytics, never()).profile(any(), anyString(), any());
  }

  @Test
  void fileStoreLimitIsEnforced() throws Exception {
    // 2 KB: accepted by the multipart parser (8 KB), stopped by the file store (1 KB).
    assertTooLarge(upload(2 * 1024));
  }

  @Test
  void multipartLimitIsEnforced() throws Exception {
    // 10 KB: rejected while the request is parsed, before any application code runs.
    assertTooLarge(upload(10 * 1024));
  }

  @Test
  void fileAtTheLimitIsAccepted() throws Exception {
    // Exactly 1 KB passes both limits and reaches the (mocked) engine.
    upload(1024);

    verify(analytics).profile(any(), anyString(), any());
  }
}
