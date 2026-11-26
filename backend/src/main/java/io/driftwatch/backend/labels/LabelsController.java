package io.driftwatch.backend.labels;

import io.driftwatch.backend.reference.InvalidOptionsException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code /api/v1/runs/{runId}/labels}: upload ground truth for a run and read the join. */
@RestController
@RequestMapping(path = "/api/v1/runs/{runId}/labels", produces = MediaType.APPLICATION_JSON_VALUE)
class LabelsController {

  private final LabelService service;
  private final JsonMapper json;

  LabelsController(LabelService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  /**
   * Uploads labels (multipart {@code labels}: a CSV with the model's record id and target columns)
   * and joins them to the run's batch. Optional {@code config} is a JSON {@code PerformanceConfig}
   * passed to the engine unchanged. Synchronous: the response carries the join report and the
   * metrics.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<LabelsResponse> upload(
      @PathVariable UUID runId,
      @RequestParam("labels") MultipartFile labels,
      @RequestParam(value = "config", required = false) String config)
      throws IOException {
    JsonNode parsedConfig = parseConfig(config);
    LabelService.Uploaded uploaded;
    try (InputStream content = labels.getInputStream()) {
      uploaded = service.upload(runId, labels.getOriginalFilename(), content, parsedConfig);
    }
    URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
    return ResponseEntity.created(location)
        .body(LabelsResponse.of(uploaded.labels(), uploaded.dataset(), json));
  }

  /** The run's current (newest) labels and their join report. */
  @GetMapping
  LabelsResponse current(@PathVariable UUID runId) {
    RunLabels labels = service.current(runId);
    return LabelsResponse.of(labels, service.datasetOf(labels), json);
  }

  /** Syntax only; the engine validates the content ({@code invalid-config}). */
  private JsonNode parseConfig(String config) {
    if (config == null) {
      return null;
    }
    try {
      JsonNode parsed = json.readTree(config);
      if (!parsed.isObject()) {
        throw new InvalidOptionsException("'config' must be a JSON object");
      }
      return parsed;
    } catch (JacksonException e) {
      throw new InvalidOptionsException("'config' is not valid JSON");
    }
  }
}
