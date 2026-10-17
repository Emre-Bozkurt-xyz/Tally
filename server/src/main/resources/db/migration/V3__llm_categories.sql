-- One answer per merchant from the language model (Phase 4 step 3). category NULL means
-- "don't know", remembered until the category list (categories_hash) changes.
CREATE TABLE llm_categories (
    merchant_key TEXT PRIMARY KEY,
    category TEXT,
    categories_hash TEXT NOT NULL,
    model TEXT NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
