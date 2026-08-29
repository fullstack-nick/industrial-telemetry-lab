CREATE TABLE collector_state (
  source_id TEXT PRIMARY KEY,
  source_epoch TEXT,
  source_cursor INTEGER NOT NULL DEFAULT 0,
  gap_detected INTEGER NOT NULL DEFAULT 0 CHECK (gap_detected IN (0, 1)),
  gap_detail TEXT,
  polling_paused INTEGER NOT NULL DEFAULT 0 CHECK (polling_paused IN (0, 1)),
  updated_at TEXT NOT NULL
);

CREATE TABLE spool_observation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source_system TEXT NOT NULL,
  source_epoch TEXT NOT NULL,
  source_sequence INTEGER NOT NULL,
  source_tag TEXT NOT NULL,
  observed_at TEXT NOT NULL,
  raw_value_json TEXT NOT NULL,
  raw_unit TEXT NOT NULL,
  source_quality_code INTEGER NOT NULL,
  persisted_at TEXT NOT NULL,
  acknowledged_at TEXT
);

CREATE INDEX idx_spool_unsent
  ON spool_observation (acknowledged_at, id);

CREATE TABLE outbound_batch (
  batch_id TEXT PRIMARY KEY,
  compressed_payload BLOB NOT NULL,
  content_digest TEXT NOT NULL,
  checksum TEXT NOT NULL,
  observation_count INTEGER NOT NULL CHECK (observation_count > 0),
  created_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('READY', 'UPLOADING', 'ACKNOWLEDGED', 'FAILED')),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT NOT NULL,
  acknowledged_at TEXT,
  last_error TEXT
);

CREATE INDEX idx_outbound_ready
  ON outbound_batch (state, next_attempt_at, created_at);

CREATE TABLE outbound_batch_item (
  batch_id TEXT NOT NULL REFERENCES outbound_batch(batch_id) ON DELETE CASCADE,
  observation_id INTEGER NOT NULL REFERENCES spool_observation(id) ON DELETE RESTRICT,
  item_order INTEGER NOT NULL,
  PRIMARY KEY (batch_id, observation_id),
  UNIQUE (batch_id, item_order)
);

CREATE INDEX idx_batch_item_observation
  ON outbound_batch_item (observation_id);

INSERT INTO collector_state (
  source_id, source_epoch, source_cursor, gap_detected, polling_paused, updated_at
) VALUES ('controller-a', NULL, 0, 0, 0, '1970-01-01T00:00:00Z');
