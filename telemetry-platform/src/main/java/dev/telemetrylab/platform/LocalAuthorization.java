package dev.telemetrylab.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalAuthorization {
  private final byte[] expected;

  LocalAuthorization(PlatformProperties properties) {
    this.expected = ("Bearer " + properties.localToken()).getBytes(StandardCharsets.UTF_8);
  }

  public void require(String authorization) {
    byte[] actual =
        authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid local bearer token is required");
    }
  }
}
