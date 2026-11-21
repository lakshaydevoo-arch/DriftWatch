package io.driftwatch.backend.reference;

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

/** {@code /api/v1/models/{modelId}/reference}: register and read a model's reference data. */
@RestController
@RequestMapping(
    path = "/api/v1/models/{modelId}/reference",
    produces = MediaType.APPLICATION_JSON_VALUE)
class ReferenceController {

  private final ReferenceService service;
  private final JsonMapper json;

  ReferenceController(ReferenceService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  /**
   * Uploads reference data (multipart {@code file}) and has the engine profile it. Optional {@code
   * options} is a JSON {@code ProfileOptions} object passed to the engine unchanged. Profiling is
   * synchronous: the response arrives when the profile is stored.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<ReferenceResponse> register(
      @PathVariable UUID modelId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "options", required = false) String options)
      throws IOException {
    JsonNode parsedOptions = parseOptions(options);
    ReferenceService.Registered registered;
    try (InputStream content = file.getInputStream()) {
      registered = service.register(modelId, file.getOriginalFilename(), content, parsedOptions);
    }
    URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
    return ResponseEntity.created(location)
        .body(ReferenceResponse.of(registered.reference(), registered.dataset(), json));
  }

  /** The model's current (most recently registered) reference. */
  @GetMapping
  ReferenceResponse current(@PathVariable UUID modelId) {
    Reference reference = service.current(modelId);
    return ReferenceResponse.of(reference, service.datasetOf(reference), json);
  }

  /** Syntax only; the engine validates the content and returns {@code invalid-options}. */
  private JsonNode parseOptions(String options) {
    if (options == null) {
      return null;
    }
    try {
      JsonNode parsed = json.readTree(options);
      if (!parsed.isObject()) {
        throw new InvalidOptionsException("'options' must be a JSON object");
      }
      return parsed;
    } catch (JacksonException e) {
      throw new InvalidOptionsException("'options' is not valid JSON");
    }
  }
}
