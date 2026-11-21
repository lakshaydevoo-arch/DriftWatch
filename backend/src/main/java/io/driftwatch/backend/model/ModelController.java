package io.driftwatch.backend.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Model registry: {@code /api/v1/models}. */
@RestController
@RequestMapping(path = "/api/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
class ModelController {

  static final int MAX_PAGE_SIZE = 100;

  private final ModelService service;

  ModelController(ModelService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<ModelResponse> create(@Valid @RequestBody CreateModelRequest request) {
    MonitoredModel model =
        service.create(
            request.name(), request.taskType(), request.targetColumn(), request.predictionColumn());
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(model.getId())
            .toUri();
    return ResponseEntity.created(location).body(ModelResponse.from(model));
  }

  @GetMapping("/{id}")
  ModelResponse get(@PathVariable UUID id) {
    return ModelResponse.from(service.get(id));
  }

  @GetMapping
  ModelPage list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return ModelPage.from(service.list(page, size));
  }
}
