# ADR 003: Event time versus processing time

- Status: Accepted
- Date: 2026-08-29

## Context

Outages and retries make arrival order differ from the time a measurement occurred. Using database insertion time for queries would misrepresent the physical sequence and make catch-up data appear current.

## Decision

Store `observed_at`, `received_at`, and `processed_at` independently. Query and TimescaleDB partition by source event time. Compute freshness from event to processing time for eligible good-quality observations. Detect sequence/time regression and retain usable values with `OUT_OF_ORDER` or `LATE_ARRIVAL`; reject unsafe future timestamps.

## Consequences

Late data appears at its real place in event-time queries and can update historical windows. Retention and replay selection must reason about event time, while raw object keys use trusted gateway receipt time. Clock discipline remains operationally important.

## Verification

`duplicate-delivery` injects delayed event timestamps and asserts `OUT_OF_ORDER` flags without losing queryability. Prometheus recording/alert rules exclude future and deliberately bad-quality data from the latency objective.
