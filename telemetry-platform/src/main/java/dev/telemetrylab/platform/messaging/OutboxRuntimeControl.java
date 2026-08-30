package dev.telemetrylab.platform.messaging;

import dev.telemetrylab.platform.PlatformProperties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class OutboxRuntimeControl {
  private final PlatformProperties properties;
  private final AtomicReference<String> failAfterConfirmTarget = new AtomicReference<>();

  public OutboxRuntimeControl(PlatformProperties properties) {
    this.properties = properties;
  }

  public boolean armConfirmCommitGap(boolean armed, UUID batchId) {
    if (armed && !properties.failureInjectionEnabled()) {
      throw new IllegalStateException("Local failure injection is disabled");
    }
    failAfterConfirmTarget.set(armed ? target(batchId) : null);
    return armed;
  }

  boolean consumeConfirmCommitGap(UUID batchId) {
    String current = failAfterConfirmTarget.get();
    return current != null
        && (current.equals("*") || current.equals(batchId.toString()))
        && failAfterConfirmTarget.compareAndSet(current, null);
  }

  private static String target(UUID batchId) {
    return batchId == null ? "*" : batchId.toString();
  }
}
