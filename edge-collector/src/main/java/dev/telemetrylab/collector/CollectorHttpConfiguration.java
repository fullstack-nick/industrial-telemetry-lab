package dev.telemetrylab.collector;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class CollectorHttpConfiguration {
  @Bean("sourceRestClient")
  RestClient sourceRestClient(CollectorProperties properties) {
    return client(properties.sourceUrl());
  }

  @Bean("gatewayRestClient")
  RestClient gatewayRestClient(CollectorProperties properties) {
    return client(properties.gatewayUrl());
  }

  @Bean
  CollectorPipeline collectorPipeline(
      CollectorRepository repository,
      @Qualifier("sourceRestClient") RestClient sourceRestClient,
      @Qualifier("gatewayRestClient") RestClient gatewayRestClient,
      CollectorProperties properties,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      java.time.Clock clock,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    return new CollectorPipeline(
        repository,
        sourceRestClient,
        gatewayRestClient,
        properties,
        objectMapper,
        clock,
        meterRegistry);
  }

  private static RestClient client(String baseUrl) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(30));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
