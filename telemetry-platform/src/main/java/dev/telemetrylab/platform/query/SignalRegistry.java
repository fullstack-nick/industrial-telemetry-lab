package dev.telemetrylab.platform.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
final class SignalRegistry {
  private final Map<String, Object> registry;

  SignalRegistry() {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    try (InputStream input =
        new ClassPathResource("contracts/signal-registry.yaml").getInputStream()) {
      registry = Map.copyOf(yaml.readValue(input, new TypeReference<>() {}));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load the signal registry", exception);
    }
  }

  Map<String, Object> document() {
    return registry;
  }
}
