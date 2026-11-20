CREATE TABLE events (
  id VARCHAR(80) PRIMARY KEY,
  account_id VARCHAR(80) NOT NULL,
  credits INTEGER NOT NULL CHECK (credits BETWEEN 1 AND 1000000),
  payload_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','PROCESSING','RETRY_WAIT','SUCCEEDED','DEAD_LETTER')),
  delivery_count INTEGER NOT NULL DEFAULT 1,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  cycle_attempt INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  lease_until TIMESTAMPTZ,
  lease_token UUID,
  last_error TEXT,
  fault_mode VARCHAR(32) NOT NULL DEFAULT 'NONE',
  failures_remaining INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX events_ready_idx ON events(next_attempt_at,created_at) WHERE status IN ('RECEIVED','RETRY_WAIT');
CREATE INDEX events_lease_idx ON events(lease_until) WHERE status='PROCESSING';
CREATE TABLE accounts (id VARCHAR(80) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0));
CREATE TABLE credit_ledger (
  event_id VARCHAR(80) PRIMARY KEY REFERENCES events(id),
  account_id VARCHAR(80) NOT NULL REFERENCES accounts(id),
  credits INTEGER NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE attempts (
  id BIGSERIAL PRIMARY KEY,
  event_id VARCHAR(80) NOT NULL REFERENCES events(id),
  number INTEGER NOT NULL,
  lease_token UUID NOT NULL UNIQUE,
  worker_id VARCHAR(80) NOT NULL,
  outcome VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
  message TEXT,
  started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  finished_at TIMESTAMPTZ,
  UNIQUE(event_id,number)
);
CREATE TABLE activity (
  id BIGSERIAL PRIMARY KEY,
  event_id VARCHAR(80) NOT NULL REFERENCES events(id),
  action VARCHAR(40) NOT NULL,
  detail TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX activity_event_idx ON activity(event_id,id);
-- Guard the append-only audit trail against accidental application updates/deletes.
CREATE FUNCTION prevent_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'activity is append-only'; END $$;
CREATE TRIGGER activity_append_only BEFORE UPDATE OR DELETE ON activity FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
