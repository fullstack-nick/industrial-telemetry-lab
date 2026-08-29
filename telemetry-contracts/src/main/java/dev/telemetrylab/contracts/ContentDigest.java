package dev.telemetrylab.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

public final class ContentDigest {
  private static final HexFormat HEX = HexFormat.of();

  private ContentDigest() {}

  public static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static String checksum(byte[] bytes) {
    return "sha256:" + HEX.formatHex(sha256(bytes));
  }

  public static String header(byte[] bytes) {
    return "sha-256=:" + Base64.getEncoder().encodeToString(sha256(bytes)) + ":";
  }

  public static boolean headerMatches(String header, byte[] bytes) {
    if (header == null) {
      return false;
    }
    return MessageDigest.isEqual(
        header(bytes).getBytes(StandardCharsets.US_ASCII),
        header.trim().getBytes(StandardCharsets.US_ASCII));
  }
}
