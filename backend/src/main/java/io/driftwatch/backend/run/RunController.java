package io.driftwatch.backend.run;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Starts monitoring runs and reports their status.
 *
 * <ul>
 *   <li>{@code POST /api/v1/models/{modelId}/runs}: upload a current batch; answers {@code 202
 *       Accepted} at once, before the comparison runs.
 *   <li>{@code GET /api/v1/runs/{runId}}: poll until {@code status} is {@code SUCCEEDED} or {@code
 *       FAILED}.
 *   <li>{@code GET /api/v1/runs/{runId}/features}: per-feature results of a successful run.
 *   <li>{@code GET /api/v1/models/{modelId}/runs}: a model's run history, newest first.
 * </ul>
 */
@RestController
class RunController {

  static final int MAX_PAGE_SIZE = 100;

  private final RunService service;
  private final RunQueries queries;
  private final JsonMapper json;

  RunController(RunService service, RunQueries queries, JsonMapper json) {
    this.service = service;
    this.queries = queries;
    this.json = json;
  }

  /**
   * Uploads a current batch (multipart {@code current}) and queues a run against the model's
   * current reference. Optional {@code config} is a JSON {@code DriftConfig} passed to the engine
   * unchanged.
   */
  @PostMapping(
      path = "/api/v1/models/{modelId}/runs",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RunResponse> submit(
      @PathVariable UUID modelId,
      @RequestParam("current") MultipartFile current,
      @RequestParam(value = "config", required = false) String config)
      throws IOException {
    JsonNode parsedConfig = parseConfig(config);
    MonitoringRun run;
    try (InputStream content = current.getInputStream()) {
      run = service.submit(modelId, current.getOriginalFilename(), content, parsedConfig);
    }
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/runs/{runId}")
            .buildAndExpand(run.getId())
            .toUri();
    return ResponseEntity.accepted()
        .location(location)
        .body(RunResponse.of(run, service.currentDatasetOf(run), json));
  }

  /** A run's status and, once finished, its summary or error. */
  @GetMapping(path = "/api/v1/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
  RunResponse get(@PathVariable UUID runId) {
    return RunResponse.of(queries.run(runId), json);
  }

  /** A model's runs, newest first; each without per-feature results. */
  @GetMapping(path = "/api/v1/models/{modelId}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
  RunPageResponse history(
      @PathVariable UUID modelId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return RunPageResponse.of(queries.history(modelId, page, size), json);
  }

  /**
   * Per-feature results of a SUCCEEDED run, in schema order. {@code drifted=true} keeps only
   * features with {@code drift_detected}, {@code drifted=false} only those without.
   */
  @GetMapping(path = "/api/v1/runs/{runId}/features", produces = MediaType.APPLICATION_JSON_VALUE)
  FeatureResultsResponse features(
      @PathVariable UUID runId, @RequestParam(required = false) Boolean drifted) {
    RunQueries.Features found = queries.features(runId);
    if (found.status() != RunStatus.SUCCEEDED) {
      throw new RunNotSucceededException(runId, found.status());
    }
    JsonNode all = json.readTree(found.features());
    ArrayNode selected = json.createArrayNode();
    for (JsonNode feature : all) {
      if (drifted == null || feature.path("drift_detected").asBoolean() == drifted) {
        selected.add(feature);
      }
    }
    return new FeatureResultsResponse(runId, all.size(), selected.size(), selected);
  }

  /** Syntax only; the engine validates the content (a bad value fails the run). */
  private JsonNode parseConfig(String config) {
    if (config == null) {
      return null;
    }
    try {
      JsonNode parsed = json.readTree(config);
      if (!parsed.isObject()) {
        throw new InvalidConfigException("'config' must be a JSON object");
      }
      return parsed;
    } catch (JacksonException e) {
      throw new InvalidConfigException("'config' is not valid JSON");
    }
  }
}
