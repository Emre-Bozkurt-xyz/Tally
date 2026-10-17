-- Phase 4 step 2: sync. Every synced row of every phone table, stored as JSON (the phone's
-- Room entity), so phone schema changes need no server migration. Last write wins by
-- updated_at; server_seq orders changes for the clients' cursors.
CREATE SEQUENCE sync_seq;

CREATE TABLE sync_rows (
    table_name  TEXT        NOT NULL,
    id          TEXT        NOT NULL,
    updated_at  BIGINT      NOT NULL,
    deleted     BOOLEAN     NOT NULL,
    data        JSONB       NOT NULL,
    server_seq  BIGINT      NOT NULL DEFAULT nextval('sync_seq'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_name, id)
);

CREATE INDEX sync_rows_server_seq ON sync_rows (server_seq);
