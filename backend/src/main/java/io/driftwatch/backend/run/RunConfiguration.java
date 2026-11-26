package io.driftwatch.backend.run;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunProperties.class)
class RunConfiguration {

  @Bean
  RunExecutor runExecutor(RunProperties properties) {
    return new RunExecutor(properties);
  }
}
