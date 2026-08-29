package dev.telemetrylab.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class GzipCodec {
  private static final int BUFFER_SIZE = 8192;

  private GzipCodec() {}

  public static byte[] compress(byte[] bytes) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
        gzip.write(bytes);
      }
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to gzip in-memory payload", exception);
    }
  }

  public static byte[] decompress(byte[] compressed, int maximumBytes) throws IOException {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return readBounded(input, maximumBytes);
    }
  }

  public static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, BUFFER_SIZE));
    byte[] buffer = new byte[BUFFER_SIZE];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maximumBytes) {
        throw new PayloadLimitException(maximumBytes);
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  public static final class PayloadLimitException extends IOException {
    public PayloadLimitException(int maximumBytes) {
      super("Payload exceeds the configured limit of " + maximumBytes + " bytes");
    }
  }
}
