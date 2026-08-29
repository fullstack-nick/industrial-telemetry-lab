package dev.telemetrylab.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.ContractObjectMapper;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class EdgeCollectorApplication {
  public static void main(String[] args) {
    SpringApplication.run(EdgeCollectorApplication.class, args);
  }

  @Bean
  Clock utcClock() {
    return Clock.systemUTC();
  }

  @Bean
  ObjectMapper contractObjectMapper() {
    return ContractObjectMapper.create();
  }
}
