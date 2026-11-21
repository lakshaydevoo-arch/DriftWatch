package io.driftwatch.backend.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/models}.
 *
 * <p>Invalid values are rejected, never silently corrected (no trimming or lower-casing), so what
 * the client sent is what gets stored.
 */
public record CreateModelRequest(
    @NotNull @Pattern(
            regexp = NAME_PATTERN,
            message =
                "must be 1-100 lowercase letters, digits or hyphens, starting and ending with a"
                    + " letter or digit")
        String name,
    @NotNull TaskType taskType,
    @Size(max = 255) @Pattern(regexp = COLUMN_PATTERN, message = COLUMN_MESSAGE) String targetColumn,
    @Size(max = 255) @Pattern(regexp = COLUMN_PATTERN, message = COLUMN_MESSAGE) String predictionColumn) {

  /** URL-safe slug, e.g. {@code credit-risk-v2}. */
  static final String NAME_PATTERN = "^[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?$";

  /**
   * CSV column names may contain spaces ("mean radius"), but not leading/trailing whitespace or
   * line breaks.
   */
  static final String COLUMN_PATTERN = "^\\S(?:[^\\r\\n]*\\S)?$";

  static final String COLUMN_MESSAGE =
      "must not be blank, have leading or trailing whitespace, or contain line breaks";

  /**
   * A model's prediction cannot also be its ground-truth label. Bean Validation reports this
   * cross-field rule under the property name {@code predictionColumnDifferentFromTarget}.
   */
  @AssertTrue(message = "must be a different column from targetColumn") boolean isPredictionColumnDifferentFromTarget() {
    return targetColumn == null
        || predictionColumn == null
        || !targetColumn.equals(predictionColumn);
  }
}
