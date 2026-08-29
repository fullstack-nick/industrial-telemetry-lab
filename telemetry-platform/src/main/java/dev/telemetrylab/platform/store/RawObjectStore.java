package dev.telemetrylab.platform.store;

import java.util.List;
import java.util.Optional;

public interface RawObjectStore {
  PutResult putIfAbsent(String objectKey, byte[] content, String checksum, String contentDigest);

  RawObject get(String objectKey);

  Optional<StoredObjectMetadata> findByBatchId(String batchId);

  Optional<StoredObjectMetadata> head(String objectKey);

  List<StoredObjectMetadata> list();

  record PutResult(boolean created, StoredObjectMetadata metadata) {}

  record RawObject(byte[] content, StoredObjectMetadata metadata) {
    public RawObject {
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }

  record StoredObjectMetadata(
      String objectKey, String checksum, String contentDigest, long contentLength) {}
}
