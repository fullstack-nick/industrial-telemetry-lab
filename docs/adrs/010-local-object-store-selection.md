# ADR 010: Local object-store selection

- Status: Accepted
- Date: 2026-08-29

## Context

The lab needs maintained, local, S3-compatible immutable object storage with conditional creation and metadata inspection. It must run without a cloud account and remain replaceable behind an application boundary.

## Decision

Use SeaweedFS 4.44 in single-node `mini` mode and access it through the AWS SDK v2 S3 client. Keep a narrow `RawObjectStore` interface for put-if-absent, metadata/read, and listing/reconciliation. Store exact gzip bytes and SHA-256 metadata; keep object keys vendor-neutral.

Configure the SDK's request and response checksum calculation as `WHEN_REQUIRED`. Newer AWS SDK releases otherwise add a default CRC32 integrity header that is not required by `PutObject` and is not consistently accepted by this S3-compatible endpoint. End-to-end integrity remains explicit: the collector sends an RFC `Content-Digest`, the raw object stores the same SHA-256 as metadata, and the worker recalculates it over the exact downloaded gzip bytes before processing.

## Consequences

The setup is compact and publicly maintained, but one local process and volume provide neither high availability nor a backup. S3 compatibility is exercised only for the behaviors used here. Replacing SeaweedFS requires rerunning conditional-write, digest, listing, and replay tests.

## Verification

Normal operation reconciles every manifest with one object and all outcomes. Outage and replay scenarios exercise unavailable storage, exact retrieval, and retained rejection input.
