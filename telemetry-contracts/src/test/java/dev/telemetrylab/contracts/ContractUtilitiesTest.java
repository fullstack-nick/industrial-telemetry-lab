package dev.telemetrylab.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContractUtilitiesTest {
  @Test
  void digestUsesTheExactCompressedBytes() {
    byte[] first = GzipCodec.compress("one".getBytes(StandardCharsets.UTF_8));
    byte[] second = first.clone();
    second[second.length - 1] ^= 1;

    assertThat(ContentDigest.headerMatches(ContentDigest.header(first), first)).isTrue();
    assertThat(ContentDigest.headerMatches(ContentDigest.header(first), second)).isFalse();
    assertThat(ContentDigest.checksum(first)).startsWith("sha256:").hasSize(71);
  }

  @Test
  void decompressionIsBounded() {
    byte[] compressed = GzipCodec.compress("1234567890".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> GzipCodec.decompress(compressed, 5))
        .isInstanceOf(GzipCodec.PayloadLimitException.class);
  }

  @Test
  void observationIdentityIsStableAndSourceSensitive() {
    String first =
        DeterministicIds.observationId("facility-alpha", "controller-a", "epoch", 42, "tag-a");
    String same =
        DeterministicIds.observationId("facility-alpha", "controller-a", "epoch", 42, "tag-a");
    String different =
        DeterministicIds.observationId("facility-alpha", "controller-a", "epoch", 42, "tag-b");

    assertThat(first).isEqualTo(same).hasSize(64);
    assertThat(different).isNotEqualTo(first);
  }
}
