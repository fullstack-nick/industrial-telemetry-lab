package dev.telemetrylab.platform.gateway;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
class GatewayRuntimeControl {
  private final AtomicBoolean overloaded = new AtomicBoolean();

  boolean overloaded() {
    return overloaded.get();
  }

  boolean setOverloaded(boolean value) {
    overloaded.set(value);
    return value;
  }
}
