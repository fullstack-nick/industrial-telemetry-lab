CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE ingestion_batch (
  batch_id UUID PRIMARY KEY,
  collector_id VARCHAR(64) NOT NULL,
  collector_version VARCHAR(64) NOT NULL,
  facility_id VARCHAR(64) NOT NULL,
  contract_version VARCHAR(64) NOT NULL,
  checksum VARCHAR(80) NOT NULL,
  content_digest VARCHAR(128) NOT NULL,
  object_key TEXT NOT NULL UNIQUE,
  received_at TIMESTAMPTZ NOT NULL,
  minimum_observed_at TIMESTAMPTZ NOT NULL,
  maximum_observed_at TIMESTAMPTZ NOT NULL,
  observation_count INTEGER NOT NULL CHECK (observation_count BETWEEN 1 AND 500),
  processing_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
  accepted_count INTEGER NOT NULL DEFAULT 0,
  flagged_count INTEGER NOT NULL DEFAULT 0,
  rejected_count INTEGER NOT NULL DEFAULT 0,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  processing_attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);

CREATE INDEX idx_ingestion_replay_range
  ON ingestion_batch (facility_id, minimum_observed_at, maximum_observed_at);

CREATE TABLE outbox_event (
  event_id UUID PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  batch_id UUID NOT NULL REFERENCES ingestion_batch(batch_id),
  payload JSONB NOT NULL,
  trace_parent VARCHAR(256),
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  last_error TEXT
);

CREATE INDEX idx_outbox_unpublished
  ON outbox_event (next_attempt_at, created_at)
  WHERE published_at IS NULL;

CREATE TABLE collector_status (
  collector_id VARCHAR(64) PRIMARY KEY,
  collector_version VARCHAR(64) NOT NULL,
  configuration_version VARCHAR(128) NOT NULL,
  source_adapter_version VARCHAR(128) NOT NULL,
  last_heartbeat TIMESTAMPTZ NOT NULL,
  last_successful_upload_at TIMESTAMPTZ,
  spool_observation_count BIGINT NOT NULL,
  oldest_unsent_observation_age_seconds BIGINT NOT NULL,
  source_connected BOOLEAN NOT NULL,
  current_status VARCHAR(32) NOT NULL
);

CREATE TABLE telemetry_sample_identity (
  observation_id CHAR(64) PRIMARY KEY,
  observed_at TIMESTAMPTZ NOT NULL,
  raw_batch_id UUID NOT NULL REFERENCES ingestion_batch(batch_id),
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE telemetry_sample (
  observation_id CHAR(64) NOT NULL,
  facility_id VARCHAR(64) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  signal_id VARCHAR(128) NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL,
  value_double DOUBLE PRECISION NOT NULL,
  unit VARCHAR(32) NOT NULL,
  quality VARCHAR(16) NOT NULL,
  flags JSONB NOT NULL DEFAULT '[]'::jsonb,
  source_system VARCHAR(64) NOT NULL,
  source_sequence BIGINT NOT NULL,
  source_epoch VARCHAR(64) NOT NULL,
  source_tag VARCHAR(256) NOT NULL,
  collector_id VARCHAR(64) NOT NULL,
  collector_version VARCHAR(64) NOT NULL,
  mapping_version VARCHAR(128) NOT NULL,
  quality_rules_version VARCHAR(128) NOT NULL,
  raw_batch_id UUID NOT NULL REFERENCES ingestion_batch(batch_id),
  PRIMARY KEY (observation_id, observed_at)
);

SELECT create_hypertable(
  'telemetry_sample',
  by_range('observed_at', INTERVAL '1 day'),
  if_not_exists => TRUE
);

CREATE INDEX idx_telemetry_query
  ON telemetry_sample (facility_id, asset_id, signal_id, observed_at DESC, observation_id DESC);

CREATE INDEX idx_telemetry_source_sequence
  ON telemetry_sample (facility_id, source_system, source_epoch, source_tag, source_sequence DESC);

CREATE TABLE replay_run (
  replay_id UUID PRIMARY KEY,
  facility_id VARCHAR(64) NOT NULL,
  from_time TIMESTAMPTZ NOT NULL,
  to_time TIMESTAMPTZ NOT NULL,
  mapping_version VARCHAR(128) NOT NULL,
  quality_rules_version VARCHAR(128) NOT NULL,
  reason TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  matching_batch_count INTEGER NOT NULL DEFAULT 0,
  processed_observation_count INTEGER NOT NULL DEFAULT 0,
  accepted_count INTEGER NOT NULL DEFAULT 0,
  flagged_count INTEGER NOT NULL DEFAULT 0,
  rejected_count INTEGER NOT NULL DEFAULT 0,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  requested_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  last_error TEXT,
  CHECK (to_time > from_time),
  CHECK (to_time - from_time <= INTERVAL '24 hours')
);

CREATE UNIQUE INDEX one_active_replay
  ON replay_run ((status IN ('PENDING', 'RUNNING')))
  WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE replay_batch (
  replay_id UUID NOT NULL REFERENCES replay_run(replay_id) ON DELETE CASCADE,
  batch_id UUID NOT NULL REFERENCES ingestion_batch(batch_id),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  processed_observation_count INTEGER NOT NULL DEFAULT 0,
  accepted_count INTEGER NOT NULL DEFAULT 0,
  flagged_count INTEGER NOT NULL DEFAULT 0,
  rejected_count INTEGER NOT NULL DEFAULT 0,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  PRIMARY KEY (replay_id, batch_id)
);

CREATE TABLE processing_attempt (
  processing_attempt_id UUID PRIMARY KEY,
  batch_id UUID NOT NULL REFERENCES ingestion_batch(batch_id),
  replay_id UUID REFERENCES replay_run(replay_id),
  mapping_version VARCHAR(128) NOT NULL,
  quality_rules_version VARCHAR(128) NOT NULL,
  attempt_number INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL,
  accepted_count INTEGER NOT NULL DEFAULT 0,
  flagged_count INTEGER NOT NULL DEFAULT 0,
  rejected_count INTEGER NOT NULL DEFAULT 0,
  duplicate_count INTEGER NOT NULL DEFAULT 0,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  last_error TEXT
);

CREATE TABLE telemetry_rejection (
  rejection_id BIGSERIAL PRIMARY KEY,
  observation_id CHAR(64) NOT NULL,
  batch_id UUID NOT NULL REFERENCES ingestion_batch(batch_id),
  replay_id UUID REFERENCES replay_run(replay_id),
  source_tag VARCHAR(256),
  reason_code VARCHAR(64) NOT NULL,
  human_readable_reason TEXT NOT NULL,
  mapping_version VARCHAR(128) NOT NULL,
  quality_rules_version VARCHAR(128) NOT NULL,
  processing_attempt_id UUID NOT NULL REFERENCES processing_attempt(processing_attempt_id),
  created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uq_rejection_delivery
  ON telemetry_rejection (
    observation_id,
    batch_id,
    mapping_version,
    quality_rules_version,
    COALESCE(replay_id, '00000000-0000-0000-0000-000000000000'::uuid),
    reason_code
  );

CREATE INDEX idx_rejection_batch ON telemetry_rejection (batch_id, created_at);
CREATE INDEX idx_processing_attempt_batch ON processing_attempt (batch_id, started_at DESC);
