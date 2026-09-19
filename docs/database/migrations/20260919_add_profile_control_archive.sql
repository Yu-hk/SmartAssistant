-- Apply only after the independent control directory and deployment source pin
-- have been prepared. This migration creates no deletion task or user pause.
BEGIN;
CREATE TABLE IF NOT EXISTS profile_control_source (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 source_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
 last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence >= 0)
);
INSERT INTO profile_control_source(singleton) VALUES(true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS profile_control_outbox (
 sequence bigint PRIMARY KEY CHECK(sequence > 0),
 event_id uuid NOT NULL UNIQUE,
 source_id uuid NOT NULL,
 user_id bigint NOT NULL CHECK(user_id > 0),
 generation bigint NOT NULL CHECK(generation > 0),
 analysis_enabled boolean NOT NULL,
 UNIQUE(user_id,generation)
);
-- Retained independently of user/account deletion. No payloads or credentials.
-- Existing nonzero generations need an explicit baseline migration; never silently
-- pretend a newly empty stream covers historical pauses/reopens.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM profile_lifecycle l WHERE (l.generation>0 OR NOT l.analysis_enabled)
   AND NOT EXISTS (SELECT 1 FROM profile_control_outbox e WHERE e.user_id=l.user_id
     AND e.generation=l.generation AND e.analysis_enabled=l.analysis_enabled)) THEN
  RAISE EXCEPTION 'Existing lifecycle requires audited control baseline';
 END IF;
END $$;
COMMIT;
