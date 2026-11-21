package io.driftwatch.backend.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client against a stub HTTP server: request format, error mapping and timeouts. The real
 * engine contract is checked separately by {@link AnalyticsEngineContractTest}.
 */
class AnalyticsClientTest {

  private static final byte[] CSV = "age\n31\n45\n".getBytes(StandardCharsets.UTF_8);
  private final JsonMapper json = JsonMapper.builder().build();

  private HttpServer server;
  private final AtomicReference<Handler> handler = new AtomicReference<>();
  private final AtomicReference<Captured> captured = new AtomicReference<>();

  /** What the stub received. */
  record Captured(String method, String path, String contentType, byte[] body) {
    String text() {
      return new String(body, StandardCharsets.ISO_8859_1);
    }
  }

  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          byte[] body;
          try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
          }
          captured.set(
              new Captured(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Content-Type"),
                  body));
          handler.get().handle(exchange);
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AnalyticsClient client(Duration readTimeout) {
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    return new AnalyticsClient(
        new AnalyticsProperties(base, Duration.ofSeconds(2), readTimeout), json);
  }

  private AnalyticsClient client() {
    return client(Duration.ofSeconds(5));
  }

  private void respond(int status, String contentType, String body) {
    handler.set(exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
  }

  private void respondJson(int status, String body) {
    respond(status, "application/json", body);
  }

  private void respondProblem(int status, String kind, String detail, String extra) {
    respond(
        status,
        "application/problem+json",
        "{\"type\": \"urn:driftwatch:problem:%s\", \"title\": \"t\", \"status\": %d, \"detail\": \"%s\"%s}"
            .formatted(kind, status, detail, extra));
  }

  private static ByteArrayResource csv() {
    return new ByteArrayResource(CSV);
  }

  // --- requests -------------------------------------------------------------------------

  @Test
  void healthIsParsed() {
    respondJson(200, "{\"status\": \"ok\", \"engine_version\": \"0.1.0.dev0\"}");

    EngineHealth health = client().health();

    assertThat(health).isEqualTo(new EngineHealth("ok", "0.1.0.dev0"));
    assertThat(captured.get().method()).isEqualTo("GET");
    assertThat(captured.get().path()).isEqualTo("/health");
  }

  @Test
  void profileSendsTheCsvAndOptionsAsMultipart() {
    respondJson(200, "{\"profile\": {\"n_rows\": 2}, \"issues\": []}");

    ProfileResult result =
        client().profile(csv(), "reference.csv", json.readTree("{\"exclude\": [\"id\"]}"));

    assertThat(result.profile().get("n_rows").asInt()).isEqualTo(2);
    assertThat(result.issues().isEmpty()).isTrue();
    Captured request = captured.get();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/v1/profile");
    // Whitespace after ";" is optional (RFC 9110), so match loosely.
    assertThat(request.contentType()).matches("multipart/form-data;\\s*boundary=\\S+");
    assertThat(request.text())
        .contains("Content-Disposition: form-data; name=\"file\"; filename=\"reference.csv\"")
        .contains("Content-Type: text/csv")
        .contains("age\n31\n45\n")
        .contains("name=\"options\"")
        .contains("{\"exclude\":[\"id\"]}");
  }

  @Test
  void profileWithoutOptionsSendsOnlyTheFile() {
    respondJson(200, "{\"profile\": {}, \"issues\": []}");

    client().profile(csv(), "reference.csv", null);

    assertThat(captured.get().text()).doesNotContain("name=\"options\"");
  }

  @Test
  void driftSendsTheProfileAsAFilePart() {
    respondJson(200, "{\"summary\": {\"n_drifted\": 0}}");
    byte[] profile = "{\"format_version\": 1}".getBytes(StandardCharsets.UTF_8);

    DriftResult result =
        client().drift(profile, csv(), "today.csv", json.readTree("{\"min_valid_count\": 50}"));

    assertThat(result.body().get("summary").get("n_drifted").asInt()).isZero();
    assertThat(captured.get().path()).isEqualTo("/v1/drift");
    assertThat(captured.get().text())
        .contains("name=\"profile\"; filename=\"profile.json\"")
        .contains("Content-Type: application/json")
        .contains("{\"format_version\": 1}")
        .contains("name=\"current\"; filename=\"today.csv\"")
        .contains("name=\"config\"")
        .contains("{\"min_valid_count\":50}");
  }

  @Test
  void largeFilesAreSentCompletely(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("big.csv");
    byte[] chunk = "1234567,abcdefg\n".getBytes(StandardCharsets.UTF_8);
    try (OutputStream out = Files.newOutputStream(file)) {
      for (int i = 0; i < 2_000_000; i++) { // 32 MB
        out.write(chunk);
      }
    }
    respondJson(200, "{\"profile\": {}, \"issues\": []}");

    client().profile(new FileSystemResource(file), "big.csv", null);

    assertThat(captured.get().body().length).isGreaterThan((int) Files.size(file));
  }

  // --- error mapping --------------------------------------------------------------------

  @Test
  void problemFromTheEngineBecomesARejection() {
    respondProblem(
        422,
        "schema-mismatch",
        "the current batch is missing monitored columns",
        ", \"errors\": [{\"code\": \"missing_column\", \"column\": \"age\"}]");

    assertThatThrownBy(() -> client().drift(new byte[] {'{', '}'}, csv(), "c.csv", null))
        .isInstanceOfSatisfying(
            AnalyticsRejectedException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(422);
              assertThat(e.getKind()).isEqualTo("schema-mismatch");
              assertThat(e.getDetail()).isEqualTo("the current batch is missing monitored columns");
              assertThat(e.getErrors())
                  .singleElement()
                  .satisfies(error -> assertThat(error).containsEntry("column", "age"));
            });
  }

  @Test
  void tooLargeUploadIsARejectionWithoutErrors() {
    respondProblem(413, "upload-too-large", "'file' exceeds the limit", "");

    assertThatThrownBy(() -> client().profile(csv(), "r.csv", null))
        .isInstanceOfSatisfying(
            AnalyticsRejectedException.class,
            e -> {
              assertThat(e.getKind()).isEqualTo("upload-too-large");
              assertThat(e.getErrors()).isEmpty();
            });
  }

  @Test
  void genericProblemIsAProtocolError() {
    // about:blank, e.g. 404 for a wrong path: a client bug, not bad user input.
    respond(404, "application/problem+json", "{\"type\": \"about:blank\", \"status\": 404}");

    assertThatThrownBy(() -> client().health()).isInstanceOf(AnalyticsProtocolException.class);
  }

  @Test
  void clientErrorWithoutProblemBodyIsAProtocolError() {
    respond(400, "text/plain", "bad");

    assertThatThrownBy(() -> client().health())
        .isInstanceOf(AnalyticsProtocolException.class)
        .hasMessageContaining("without a problem body");
  }

  @Test
  void serverErrorsMeanUnavailable() {
    respond(500, "application/json", "{}");
    assertThatThrownBy(() -> client().health()).isInstanceOf(AnalyticsUnavailableException.class);

    respond(503, "text/html", "<html>maintenance</html>");
    assertThatThrownBy(() -> client().health())
        .isInstanceOf(AnalyticsUnavailableException.class)
        .hasMessageContaining("HTTP 503");
  }

  @Test
  void invalidJsonOnSuccessIsAProtocolError() {
    respondJson(200, "{not json");

    assertThatThrownBy(() -> client().health())
        .isInstanceOf(AnalyticsProtocolException.class)
        .hasMessageContaining("invalid JSON");
  }

  @Test
  void missingResponseFieldIsAProtocolError() {
    respondJson(200, "{\"issues\": []}");

    assertThatThrownBy(() -> client().profile(csv(), "r.csv", null))
        .isInstanceOf(AnalyticsProtocolException.class)
        .hasMessageContaining("lacks 'profile'");
  }

  // --- connectivity ---------------------------------------------------------------------

  @Test
  void refusedConnectionMeansUnavailable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    AnalyticsClient client =
        new AnalyticsClient(
            new AnalyticsProperties(
                URI.create("http://127.0.0.1:" + closedPort),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)),
            json);

    assertThatThrownBy(client::health)
        .isInstanceOf(AnalyticsUnavailableException.class)
        .hasMessageContaining("http://127.0.0.1:" + closedPort)
        .hasMessageContaining("health");
  }

  @Test
  void slowEngineTimesOut() {
    handler.set(exchange -> {
      try {
        Thread.sleep(3_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    long start = System.nanoTime();

    assertThatThrownBy(() -> client(Duration.ofMillis(300)).health())
        .isInstanceOf(AnalyticsUnavailableException.class)
        .hasMessageContaining("Timeout");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void timeoutsMustBePositive() {
    URI base = URI.create("http://localhost:8000");
    assertThatThrownBy(() -> new AnalyticsProperties(base, Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AnalyticsProperties(base, Duration.ofSeconds(1), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
