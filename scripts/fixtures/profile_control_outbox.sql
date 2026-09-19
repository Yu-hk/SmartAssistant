-- SYNTHETIC ONLY: deliberately not a production migration or backup authority.
BEGIN;
DO $$ BEGIN
 IF current_database() <> 'profile_control_outbox_fixture' THEN
  RAISE EXCEPTION 'Synthetic control database required';
 END IF;
 IF EXISTS (SELECT 1 FROM public.profile_lifecycle) THEN
  RAISE EXCEPTION 'Empty baseline required; historical coverage not implemented';
 END IF;
END $$;

CREATE TABLE public.profile_control_stream (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 source_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
 last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence >= 0)
);
INSERT INTO public.profile_control_stream(singleton) VALUES(true);
CREATE TABLE public.profile_control_event (
 sequence bigint PRIMARY KEY CHECK(sequence > 0),
 event_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
 source_id uuid NOT NULL,
 user_id bigint NOT NULL CHECK(user_id > 0),
 generation bigint NOT NULL CHECK(generation >= 0),
 analysis_enabled boolean NOT NULL,
 UNIQUE(user_id,generation)
);
-- No FK to users: an event must not disappear through account cascade deletion.
-- No message, profile, order, credentials or external-delivery ACK stored here.

CREATE FUNCTION public.capture_profile_control() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE stream public.profile_control_stream;
BEGIN
 IF TG_OP = 'DELETE' THEN
  RAISE EXCEPTION 'Control retention policy required before lifecycle deletion';
 ELSIF TG_OP = 'INSERT' THEN
  IF NEW.generation <> 0 OR NOT NEW.analysis_enabled THEN
   RAISE EXCEPTION 'New lifecycle must start enabled at generation zero';
  END IF;
 ELSE
  IF NEW.user_id <> OLD.user_id THEN RAISE EXCEPTION 'Lifecycle identity is immutable'; END IF;
  IF NEW.generation = OLD.generation AND NEW.analysis_enabled = OLD.analysis_enabled THEN RETURN NEW; END IF;
  IF NEW.generation <> OLD.generation + 1 THEN
   RAISE EXCEPTION 'Control transition requires the next generation';
  END IF;
 END IF;
 -- A transactional counter, not nextval(): rollback leaves no sequence hole.
 -- The row lock serializes allocation until commit, including across users.
 UPDATE public.profile_control_stream SET last_sequence=last_sequence+1
  WHERE singleton RETURNING * INTO stream;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control stream'; END IF;
 INSERT INTO public.profile_control_event(sequence,source_id,user_id,generation,analysis_enabled)
  VALUES(stream.last_sequence,stream.source_id,NEW.user_id,NEW.generation,NEW.analysis_enabled);
 RETURN NEW;
END $$;
CREATE TRIGGER profile_control_capture AFTER INSERT OR UPDATE OR DELETE
 ON public.profile_lifecycle FOR EACH ROW EXECUTE FUNCTION public.capture_profile_control();
COMMIT;
