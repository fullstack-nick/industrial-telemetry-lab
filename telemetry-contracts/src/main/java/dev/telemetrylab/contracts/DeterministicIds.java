package dev.telemetrylab.contracts;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class DeterministicIds {
  private DeterministicIds() {}

  public static String observationId(
      String facilityId,
      String sourceSystem,
      String sourceEpoch,
      long sourceSequence,
      String sourceTag) {
    String material =
        String.join(
            "\u001f",
            facilityId,
            sourceSystem,
            sourceEpoch,
            Long.toString(sourceSequence),
            sourceTag);
    return HexFormat.of()
        .formatHex(ContentDigest.sha256(material.getBytes(StandardCharsets.UTF_8)));
  }
}
