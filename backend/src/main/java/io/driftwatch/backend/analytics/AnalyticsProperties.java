package io.driftwatch.backend.analytics;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code driftwatch.analytics.*}: where the analytics engine is and how long to wait for it. */
@Validated
@ConfigurationProperties("driftwatch.analytics")
public record AnalyticsProperties(
    @NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

  public AnalyticsProperties {
    if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())) {
      throw new IllegalArgumentException("driftwatch.analytics.connect-timeout must be positive");
    }
    if (readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero())) {
      throw new IllegalArgumentException("driftwatch.analytics.read-timeout must be positive");
    }
  }
}
