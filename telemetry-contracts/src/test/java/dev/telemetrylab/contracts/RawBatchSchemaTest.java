package dev.telemetrylab.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RawBatchSchemaTest {
  private static final ObjectMapper MAPPER = ContractObjectMapper.create();
  private static Schema schema;
  private static JsonNode valid;

  @BeforeAll
  static void loadContract() throws Exception {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    try (InputStream schemaInput =
            RawBatchSchemaTest.class.getResourceAsStream(
                "/contracts/raw-observation.batch.v1.schema.json");
        InputStream exampleInput =
            RawBatchSchemaTest.class.getResourceAsStream(
                "/contracts/examples/valid-raw-batch.json")) {
      schema = registry.getSchema(schemaInput);
      valid = MAPPER.readTree(exampleInput);
    }
  }

  @Test
  void validExampleConforms() {
    assertThat(schema.validate(valid)).isEmpty();
  }

  @Test
  void missingRequiredFieldFails() {
    JsonNode changed = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed).remove("facilityId");

    assertThat(schema.validate(changed)).isNotEmpty();
  }

  @Test
  void additiveOptionalFieldRemainsCompatible() {
    JsonNode changed = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("optionalNote", "compatible");

    assertThat(schema.validate(changed)).isEmpty();
  }

  @Test
  void wrongValueTypeFails() {
    JsonNode changed = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("observations").get(0))
        .put("rawValue", "not-a-number");

    assertThat(schema.validate(changed)).isNotEmpty();
  }

  @Test
  void unsupportedContractVersionFails() {
    JsonNode changed = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed)
        .put("contractVersion", "raw-observation.batch.v2");

    assertThat(schema.validate(changed)).isNotEmpty();
  }

  @Test
  void invalidTimestampCannotBeDeserializedIntoTheContract() {
    JsonNode changed = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("observations").get(0))
        .put("observedAt", "not-a-timestamp");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> MAPPER.treeToValue(changed, RawObservationBatch.class)))
        .isNotNull();
  }

  @Test
  void batchSizeLimitIsEnforced() {
    JsonNode changed = valid.deepCopy();
    ArrayNode observations = (ArrayNode) changed.get("observations");
    JsonNode template = observations.get(0).deepCopy();
    while (observations.size() <= 500) {
      observations.add(template.deepCopy());
    }

    assertThat(schema.validate(changed)).isNotEmpty();
  }
}
