package dev.telemetrylab.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.ContractObjectMapper;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TelemetryPlatformApplication {
  public static void main(String[] args) {
    SpringApplication.run(TelemetryPlatformApplication.class, args);
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
