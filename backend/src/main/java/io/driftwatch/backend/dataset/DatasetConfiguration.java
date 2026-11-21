package io.driftwatch.backend.dataset;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatasetProperties.class)
class DatasetConfiguration {

  @Bean
  ContentAddressedFileStore contentAddressedFileStore(DatasetProperties properties) {
    return new ContentAddressedFileStore(
        properties.storageRoot(), properties.maxUploadSize().toBytes());
  }
}
