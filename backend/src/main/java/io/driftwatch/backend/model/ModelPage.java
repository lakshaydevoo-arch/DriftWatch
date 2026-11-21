package io.driftwatch.backend.model;

import java.util.List;
import org.springframework.data.domain.Page;

/** One page of models. A stable, explicit shape instead of serialising Spring's {@code Page}. */
public record ModelPage(
    List<ModelResponse> items, int page, int size, long totalItems, int totalPages) {

  static ModelPage from(Page<MonitoredModel> page) {
    return new ModelPage(
        page.getContent().stream().map(ModelResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
