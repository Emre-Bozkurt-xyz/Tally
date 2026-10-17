-- Phase 4 step 1: the schema starts empty; sync tables arrive with the sync protocol.
-- server_meta holds small facts about this server (e.g. when it was first started).
CREATE TABLE server_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO server_meta (key, value) VALUES ('created_at', now()::text);
