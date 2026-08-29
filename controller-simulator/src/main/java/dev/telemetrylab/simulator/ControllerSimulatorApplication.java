package dev.telemetrylab.simulator;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ControllerSimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(ControllerSimulatorApplication.class, args);
  }

  @Bean
  Clock utcClock() {
    return Clock.systemUTC();
  }
}
