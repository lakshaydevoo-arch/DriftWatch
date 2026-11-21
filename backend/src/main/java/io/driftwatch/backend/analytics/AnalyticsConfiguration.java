package io.driftwatch.backend.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
class AnalyticsConfiguration {

  @Bean
  AnalyticsClient analyticsClient(AnalyticsProperties properties, JsonMapper json) {
    return new AnalyticsClient(properties, json);
  }
}
