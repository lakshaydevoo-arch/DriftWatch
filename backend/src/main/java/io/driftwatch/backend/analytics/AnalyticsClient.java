package io.driftwatch.backend.analytics;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for the stateless analytics engine ({@code analytics/README.md}, "API").
 *
 * <p>Every failure is one of three {@link AnalyticsException}s:
 *
 * <ul>
 *   <li>{@link AnalyticsRejectedException}: the engine rejected the input (4xx problem). Show it to
 *       the user; retrying will not help.
 *   <li>{@link AnalyticsUnavailableException}: unreachable, timed out, or 5xx. May succeed later.
 *   <li>{@link AnalyticsProtocolException}: the response breaks the contract (bug or version
 *       mismatch).
 * </ul>
 *
 * <p>The client never retries. The engine is stateless, so requests are safe to repeat, but
 * re-sending a 100 MB upload doubles the load; the caller (the monitoring-run lifecycle) decides.
 *
 * <p>Response bodies are returned as JSON trees: the engine owns its data model, and the backend
 * stores profiles and results as JSON rather than re-modelling every field.
 */
public class AnalyticsClient {

  static final String PROBLEM_TYPE_PREFIX = "urn:driftwatch:problem:";
  private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");
  private static final TypeReference<List<Map<String, Object>>> ERROR_LIST =
      new TypeReference<>() {};

  private final RestClient http;
  private final JsonMapper json;
  private final URI baseUrl;

  public AnalyticsClient(AnalyticsProperties properties, JsonMapper json) {
    HttpClient jdkClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            // The engine speaks HTTP/1.1 (uvicorn); skip the HTTP/2 upgrade attempt.
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    this.http =
        RestClient.builder()
            .baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory)
            .build();
    this.json = json;
    this.baseUrl = properties.baseUrl();
  }

  /** Engine liveness and version. */
  public EngineHealth health() {
    JsonNode body = execute("health", http.get().uri("/health"));
    return new EngineHealth(text(body, "status"), text(body, "engine_version"));
  }

  /**
   * Infers the schema of a reference CSV and builds its profile ({@code POST /v1/profile}).
   *
   * @param options JSON {@code ProfileOptions}, or {@code null} for defaults
   */
  public ProfileResult profile(Resource csv, String filename, JsonNode options) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("file", filePart("file", filename, TEXT_CSV, csv));
    if (options != null) {
      parts.add("options", json.writeValueAsString(options));
    }
    JsonNode body = execute("profile", post("/v1/profile", parts));
    return new ProfileResult(require(body, "profile"), require(body, "issues"));
  }

  /**
   * Compares a current batch with a stored profile ({@code POST /v1/drift}).
   *
   * @param profileJson the profile exactly as stored (sent as a file part: profiles can exceed the
   *     1 MB limit the engine's framework applies to ordinary form fields)
   * @param config JSON {@code DriftConfig}, or {@code null} for defaults
   */
  public DriftResult drift(byte[] profileJson, Resource current, String filename, JsonNode config) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "profile",
        filePart(
            "profile",
            "profile.json",
            MediaType.APPLICATION_JSON,
            new ByteArrayResource(profileJson)));
    parts.add("current", filePart("current", filename, TEXT_CSV, current));
    if (config != null) {
      parts.add("config", json.writeValueAsString(config));
    }
    return new DriftResult(execute("drift", post("/v1/drift", parts)));
  }

  /**
   * Joins ground-truth labels to a batch by record id ({@code POST /v1/performance}).
   *
   * @param profileJson the profile exactly as stored (sent as a file part, like for drift)
   */
  public PerformanceResult performance(
      byte[] profileJson,
      Resource current,
      String currentFilename,
      Resource labels,
      String labelsFilename) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add(
        "profile",
        filePart(
            "profile",
            "profile.json",
            MediaType.APPLICATION_JSON,
            new ByteArrayResource(profileJson)));
    parts.add("current", filePart("current", currentFilename, TEXT_CSV, current));
    parts.add("labels", filePart("labels", labelsFilename, TEXT_CSV, labels));
    JsonNode body = execute("performance", post("/v1/performance", parts));
    return new PerformanceResult(require(body, "join"), text(body, "engine_version"));
  }

  /**
   * A file part. Built as an {@link HttpEntity} for the servlet-side {@code
   * FormHttpMessageConverter}; Spring's {@code MultipartBodyBuilder} would pull in the reactive
   * stack, which this application does not use.
   */
  private static HttpEntity<Resource> filePart(
      String name, String filename, MediaType type, Resource content) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(type);
    headers.setContentDisposition(
        ContentDisposition.formData().name(name).filename(filename).build());
    return new HttpEntity<>(content, headers);
  }

  private RestClient.RequestHeadersSpec<?> post(String path, MultiValueMap<String, ?> parts) {
    return http.post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(parts);
  }

  private JsonNode execute(String operation, RestClient.RequestHeadersSpec<?> request) {
    try {
      return request.exchange((req, response) -> handle(operation, response));
    } catch (ResourceAccessException e) {
      // Connection refused, DNS failure, connect or read timeout, broken connection.
      throw new AnalyticsUnavailableException(
          "Analytics engine at "
              + baseUrl
              + " unavailable during "
              + operation
              + ": "
              + rootMessage(e),
          e);
    }
  }

  private JsonNode handle(String operation, ClientHttpResponse response) throws IOException {
    int status = response.getStatusCode().value();
    byte[] body;
    try (InputStream in = response.getBody()) {
      body = in.readAllBytes();
    }
    if (status >= 200 && status < 300) {
      try {
        return json.readTree(body);
      } catch (JacksonException e) {
        throw new AnalyticsProtocolException(
            "Analytics engine returned invalid JSON for " + operation, e);
      }
    }
    if (status >= 400 && status < 500) {
      throw rejected(operation, status, response.getHeaders(), body);
    }
    throw new AnalyticsUnavailableException(
        "Analytics engine returned HTTP " + status + " for " + operation, null);
  }

  private AnalyticsException rejected(
      String operation, int status, HttpHeaders headers, byte[] body) {
    MediaType type = headers.getContentType();
    if (type == null || !type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)) {
      return new AnalyticsProtocolException(
          "Analytics engine returned HTTP " + status + " without a problem body for " + operation,
          null);
    }
    try {
      JsonNode problem = json.readTree(body);
      String problemType = text(problem, "type");
      if (problemType == null || !problemType.startsWith(PROBLEM_TYPE_PREFIX)) {
        // e.g. about:blank for an unknown path: a client bug, not bad user input.
        return new AnalyticsProtocolException(
            "Analytics engine returned HTTP " + status + " (" + problemType + ") for " + operation,
            null);
      }
      JsonNode errors = problem.get("errors");
      return new AnalyticsRejectedException(
          status,
          problemType.substring(PROBLEM_TYPE_PREFIX.length()),
          text(problem, "detail"),
          errors == null || errors.isNull() ? List.of() : json.convertValue(errors, ERROR_LIST));
    } catch (JacksonException e) {
      return new AnalyticsProtocolException(
          "Analytics engine returned an unreadable problem body for " + operation, e);
    }
  }

  private static JsonNode require(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      throw new AnalyticsProtocolException("Analytics engine response lacks '" + field + "'", null);
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName()
        + (root.getMessage() == null ? "" : " (" + root.getMessage() + ")");
  }
}
