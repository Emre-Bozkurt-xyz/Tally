-- Receipts by email (Phase 7 step 4). Where the poller left off in each mailbox: IMAP UIDs
-- only mean something within one UIDVALIDITY.
CREATE TABLE email_state (
    mailbox TEXT PRIMARY KEY,
    uid_validity BIGINT NOT NULL,
    last_uid BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One transaction per order ("walmart:200012345"), so a later receipt for the same order
-- updates its amount instead of adding another. No email content is kept.
CREATE TABLE email_orders (
    order_key TEXT PRIMARY KEY,
    transaction_id TEXT NOT NULL,
    total_cents BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
