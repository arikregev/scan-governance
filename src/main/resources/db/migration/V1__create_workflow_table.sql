CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE workflow (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    request_ref     UUID,
    workflow_id     VARCHAR(500),
    run_id          VARCHAR(255),
    scanning_tool   VARCHAR(100),
    scan_type       VARCHAR(50),
    status          VARCHAR(50)  NOT NULL DEFAULT 'QUEUED',
    result          JSONB,
    input           JSONB,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    duration_seconds INTEGER,
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT now()
);

-- Uniqueness: one row per (workflow_id, run_id). run_id may be NULL initially.
CREATE UNIQUE INDEX uq_workflow_run
    ON workflow (workflow_id, run_id)
    WHERE run_id IS NOT NULL;

-- Prevent duplicate un-resolved rows for the same (workflow_id, request_ref)
CREATE UNIQUE INDEX uq_workflow_request
    ON workflow (workflow_id, request_ref)
    WHERE request_ref IS NOT NULL;

-- Efficient polling of non-terminal rows
CREATE INDEX idx_workflow_status ON workflow (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');

CREATE INDEX idx_workflow_workflow_id ON workflow (workflow_id);
