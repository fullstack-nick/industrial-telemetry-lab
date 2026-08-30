package dev.telemetrylab.platform.store;

import dev.telemetrylab.platform.PlatformProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

@Component
public class S3RawObjectStore implements RawObjectStore {
  private final S3Client client;
  private final String bucket;
  private final AtomicBoolean bucketReady = new AtomicBoolean();
  private final Object bucketInitializationLock = new Object();

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The S3 client is an application-scoped, thread-safe Spring infrastructure bean")
  public S3RawObjectStore(S3Client client, PlatformProperties properties) {
    this.client = client;
    this.bucket = properties.rawStoreBucket();
  }

  @Override
  @WithSpan("raw_object_store.put_if_absent")
  public PutResult putIfAbsent(
      String objectKey, byte[] content, String checksum, String contentDigest) {
    ensureBucket();
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .ifNoneMatch("*")
              .contentType("application/json")
              .contentEncoding("gzip")
              .metadata(Map.of("sha256", checksum, "content-digest", contentDigest))
              .build(),
          RequestBody.fromBytes(content));
      return new PutResult(
          true, new StoredObjectMetadata(objectKey, checksum, contentDigest, content.length));
    } catch (S3Exception exception) {
      if (exception.statusCode() != 409 && exception.statusCode() != 412) {
        throw exception;
      }
      StoredObjectMetadata existing =
          head(objectKey)
              .orElseThrow(() -> new IllegalStateException("Object conflict without object"));
      if (!checksum.equals(existing.checksum())) {
        throw new RawObjectConflictException(objectKey, checksum, existing.checksum());
      }
      return new PutResult(false, existing);
    }
  }

  @Override
  @WithSpan("raw_object_store.get")
  public RawObject get(String objectKey) {
    ensureBucket();
    StoredObjectMetadata metadata =
        head(objectKey).orElseThrow(() -> new RawObjectMissingException(objectKey));
    byte[] content =
        client
            .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
            .asByteArray();
    return new RawObject(content, metadata);
  }

  @Override
  @WithSpan("raw_object_store.find_by_batch_id")
  public Optional<StoredObjectMetadata> findByBatchId(String batchId) {
    ensureBucket();
    String suffix = "batch=" + batchId + ".json.gz";
    String continuation = null;
    do {
      var response =
          client.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix("raw-observations/")
                  .continuationToken(continuation)
                  .build());
      for (S3Object object : response.contents()) {
        if (object.key().endsWith(suffix)) {
          return head(object.key());
        }
      }
      continuation = response.isTruncated() ? response.nextContinuationToken() : null;
    } while (continuation != null);
    return Optional.empty();
  }

  @Override
  public Optional<StoredObjectMetadata> head(String objectKey) {
    ensureBucket();
    try {
      HeadObjectResponse response =
          client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
      return Optional.of(metadata(objectKey, response));
    } catch (NoSuchKeyException exception) {
      return Optional.empty();
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        return Optional.empty();
      }
      throw exception;
    }
  }

  @Override
  public List<StoredObjectMetadata> list() {
    ensureBucket();
    List<StoredObjectMetadata> objects = new ArrayList<>();
    String continuation = null;
    do {
      var response =
          client.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix("raw-observations/")
                  .continuationToken(continuation)
                  .build());
      for (S3Object object : response.contents()) {
        head(object.key()).ifPresent(objects::add);
      }
      continuation = response.isTruncated() ? response.nextContinuationToken() : null;
    } while (continuation != null);
    return List.copyOf(objects);
  }

  private void ensureBucket() {
    if (bucketReady.get()) {
      return;
    }
    synchronized (bucketInitializationLock) {
      if (bucketReady.get()) {
        return;
      }
      try {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      } catch (S3Exception exception) {
        if (exception.statusCode() != 404) {
          throw exception;
        }
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      }
      bucketReady.set(true);
    }
  }

  private static StoredObjectMetadata metadata(String key, HeadObjectResponse response) {
    return new StoredObjectMetadata(
        key,
        response.metadata().get("sha256"),
        response.metadata().get("content-digest"),
        response.contentLength());
  }

  public static final class RawObjectConflictException extends RuntimeException {
    public RawObjectConflictException(String key, String requestedChecksum, String storedChecksum) {
      super(
          "Raw object conflict for "
              + key
              + ": requested "
              + requestedChecksum
              + " but stored "
              + storedChecksum);
    }
  }

  public static final class RawObjectMissingException extends RuntimeException {
    public RawObjectMissingException(String key) {
      super("Raw object is missing: " + key);
    }
  }
}
