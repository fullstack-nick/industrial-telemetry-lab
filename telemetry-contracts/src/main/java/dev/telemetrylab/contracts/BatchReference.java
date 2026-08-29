package dev.telemetrylab.contracts;

public record BatchReference(
    String eventType,
    String batchId,
    String objectKey,
    String checksum,
    String replayId,
    String mappingVersion,
    String qualityRulesVersion,
    String replayFrom,
    String replayTo) {
  public static BatchReference live(String batchId, String objectKey, String checksum) {
    return new BatchReference(
        "RawBatchStored", batchId, objectKey, checksum, null, null, null, null, null);
  }
}
