package io.driftwatch.backend.dataset;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code driftwatch.datasets.*}. Validated at startup, so a missing or invalid setting stops the
 * application instead of failing on the first upload.
 */
@Validated
@ConfigurationProperties("driftwatch.datasets")
public record DatasetProperties(@NotNull Path storageRoot, @NotNull DataSize maxUploadSize) {

  public DatasetProperties {
    if (maxUploadSize != null && maxUploadSize.toBytes() < 1) {
      throw new IllegalArgumentException("driftwatch.datasets.max-upload-size must be positive");
    }
  }
}
