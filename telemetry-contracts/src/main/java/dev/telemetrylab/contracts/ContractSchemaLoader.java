package dev.telemetrylab.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;

public final class ContractSchemaLoader {
  private final ObjectMapper objectMapper;

  public ContractSchemaLoader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy();
  }

  public JsonNode load(String classpathResource) throws IOException {
    try (InputStream input = ContractSchemaLoader.class.getResourceAsStream(classpathResource)) {
      if (input == null) {
        throw new IOException("Contract schema not found: " + classpathResource);
      }
      return objectMapper.readTree(input);
    }
  }
}
