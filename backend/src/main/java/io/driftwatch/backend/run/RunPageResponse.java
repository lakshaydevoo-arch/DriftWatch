package io.driftwatch.backend.run;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** One page of a model's runs, newest first. Same shape as the model list page. */
public record RunPageResponse(
    List<RunResponse> items, int page, int size, long totalItems, int totalPages) {

  static RunPageResponse of(RunQueries.RunPage page, JsonMapper json) {
    return new RunPageResponse(
        page.items().stream().map(row -> RunResponse.of(row, json)).toList(),
        page.page(),
        page.size(),
        page.totalItems(),
        (int) ((page.totalItems() + page.size() - 1) / page.size()));
  }
}
