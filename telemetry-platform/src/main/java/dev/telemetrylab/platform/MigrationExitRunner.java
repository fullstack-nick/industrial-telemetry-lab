package dev.telemetrylab.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "telemetry.platform.mode", havingValue = "migrate")
class MigrationExitRunner {
  private final ApplicationContext context;

  MigrationExitRunner(ApplicationContext context) {
    this.context = context;
  }

  @EventListener(ApplicationReadyEvent.class)
  void exitAfterMigration() {
    int exitCode = SpringApplication.exit(context, () -> 0);
    System.exit(exitCode);
  }
}
