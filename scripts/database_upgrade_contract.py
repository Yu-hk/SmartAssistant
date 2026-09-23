"""Repository migration contracts, ONLY within bootstrap's owned PG fixture.

The consolidated schema is a named starting point, not a reconstructed historical
database. Never use this rehearsal ledger to baseline an existing production DB.
"""
import hashlib
import re

ORDER = '''20260809_add_admin_console_state
20260809_add_routing_call_log_ownership
20260809_add_routing_call_log_token_usage
20260809_add_routing_call_log_tool_prompt_audit
20260810_add_admin_faq_import_source
20260813_add_workflow_versions
20260827_add_routing_call_log_request_id
20260827_add_user_notifications
20260827_add_workflow_recovery_jobs
20260827_add_workflow_recovery_notification_outbox
20260827_add_workflow_recovery_result
20260828_add_product_category
20260828_add_product_relations
20260828_add_user_external_identities
20260831_expand_product_catalog
20260902_add_ecommerce_user_profiles
20260902_add_order_after_sales
20260902_enforce_single_active_conversation
20260914_add_product_structured_features
20260914_add_product_intake
20260918_add_profile_lifecycle
20260919_add_governed_agent_memory
20260919_add_profile_cleanup_jobs
20260919_add_profile_commit_candidates
20260919_add_profile_entity_facts
20260919_add_profile_request_admission
20260919_add_profile_control_archive
20260923_add_site_visits'''.splitlines()
WRAPPED = frozenset(('20260831_expand_product_catalog', '20260914_add_product_structured_features',
                     '20260914_add_product_intake', '20260919_add_profile_control_archive'))


def load_inputs(repo):
    directory = repo / 'docs/database/migrations'
    expected = {name + '.sql' for name in ORDER}
    if len(expected) != len(ORDER) or {p.name for p in directory.glob('*.sql')} != expected:
        raise ValueError('Migration inventory changed; review explicit ordering')
    return {'docs/database/migrations/' + name + '.sql': (directory / (name + '.sql')).read_bytes()
            for name in ORDER}


def body(name, data):
    if name not in ORDER:
        raise ValueError('Unknown migration')
    text = data.decode('utf-8').replace('\r\n', '\n')
    if name in WRAPPED:
        # Explicitly reviewed wrappers, NOT a generic SQL transaction parser.
        match = re.fullmatch(r'((?:--[^\n]*\n|\s)*?)BEGIN;\s*(.*?)\s*COMMIT;\s*', text, re.S)
        if not match:
            raise ValueError('Reviewed transaction wrapper changed')
        text = match[1] + match[2]
    if re.search(r'^\s*(?:BEGIN|COMMIT|ROLLBACK|START TRANSACTION)\s*;', text, re.M | re.I):
        raise ValueError('Unexpected top-level transaction control')
    return text


def migration_sql(name, data):
    content = body(name, data)
    digest = hashlib.sha256(data).hexdigest()
    # SQL literal quoting keeps nested DO blocks and apostrophes intact.
    literal = "'" + content.replace("'", "''") + "'"
    return """BEGIN;
SET LOCAL lock_timeout='3s'; SET LOCAL statement_timeout='30s';
DO $fixture$ BEGIN
 IF current_database() NOT IN ('bootstrap_fixture_source','bootstrap_fixture_restored') THEN
  RAISE EXCEPTION 'Owned bootstrap fixture database required'; END IF;
END $fixture$;
SELECT pg_advisory_xact_lock(20092026);
CREATE TABLE IF NOT EXISTS public.fixture_migration_history(
 name text PRIMARY KEY, sha256 char(64) NOT NULL, applied_at timestamptz NOT NULL DEFAULT now());
DO $fixture$ BEGIN
 IF EXISTS(SELECT 1 FROM public.fixture_migration_history WHERE name='%s' AND sha256<>'%s') THEN
  RAISE EXCEPTION 'Applied migration checksum changed';
 ELSIF NOT EXISTS(SELECT 1 FROM public.fixture_migration_history WHERE name='%s') THEN
  EXECUTE %s;
  INSERT INTO public.fixture_migration_history(name,sha256) VALUES('%s','%s');
 END IF;
END $fixture$;
COMMIT;
""" % (name, digest, name, literal, name, digest)


def require(value, expected, label):
    if value != expected:
        raise AssertionError(label)


def reject(sql, statement, sqlstate):
    try:
        sql(statement)
    except RuntimeError as error:
        if str(error) != 'Fixture command failed SQLSTATE=' + sqlstate:
            raise
    else:
        raise AssertionError('Expected SQLSTATE ' + sqlstate)


def upgrade(sql, inputs):
    """Apply explicit sequence once; checksum failures and reruns are fail-closed."""
    for name in ORDER:
        sql(migration_sql(name, inputs['docs/database/migrations/' + name + '.sql']))
    require(sql('SELECT count(*) FROM fixture_migration_history;'), str(len(ORDER)), 'Migration count')
    # User-owned changes must survive subsequent invocations, including catalog and
    # an in-flight session. Blindly replaying old SQL would overwrite both.
    sql("""UPDATE products SET price=1888 WHERE product_code='AIRPODS-PRO';
INSERT INTO conversation_session_state(user_id,session_id,status)
 VALUES(92001,'fixture-running','ACTIVE_RUNNING');""")
    before = sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY name)) FROM fixture_migration_history t;")
    for name in ORDER:
        sql(migration_sql(name, inputs['docs/database/migrations/' + name + '.sql']))
    require(sql("SELECT status FROM conversation_session_state WHERE user_id=92001;"), 'ACTIVE_RUNNING', 'Running session changed')
    require(sql("SELECT price FROM products WHERE product_code='AIRPODS-PRO';"), '1888.00', 'Catalog edit overwritten')
    require(sql("SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY name)) FROM fixture_migration_history t;"), before, 'Ledger changed on rerun')
    name = ORDER[0]
    reject(sql, migration_sql(name, inputs['docs/database/migrations/' + name + '.sql'] + b'\n-- drift'), 'P0001')
    # Migration body and ledger insert must roll back together on a midway failure.
    sql("DELETE FROM fixture_migration_history WHERE name='" + name + "';")
    reject(sql, migration_sql(name, b'CREATE TABLE fixture_rollback(id int); SELECT 1/0;'), '22012')
    require(sql("SELECT to_regclass('public.fixture_rollback') IS NULL;"), 't', 'DDL escaped rollback')
    require(sql("SELECT count(*) FROM fixture_migration_history WHERE name='" + name + "';"), '0', 'Failed migration recorded')
    sql(migration_sql(name, inputs['docs/database/migrations/' + name + '.sql']))
    return {'migrations': len(ORDER), 'rerunSkipsApplied': True, 'checksumDriftRejected': True,
            'ddlAndLedgerRollbackTogether': True, 'baseline': 'consolidated-schema-plus-public-seed'}


def behavior(sql):
    """Exercise real constraints/trigger in a transaction, then undo fixture rows."""
    statements = [
        ("INSERT INTO conversation_session_state(user_id,session_id,status) VALUES(92001,'duplicate','ACTIVE_IDLE');", '23505'),
        ("UPDATE products SET weight_grams=-1 WHERE product_code='AIRPODS-PRO';", '23514'),
        ("INSERT INTO order_after_sales(request_id,order_id,user_id,request_type,reason) VALUES('fixture','missing-order',92001,'refund','fixture');", '23503'),
        ("INSERT INTO workflow_versions(workflow_key,version,status,definition) VALUES('fixture',1,'PUBLISHED','{}');", '23514'),
    ]
    for statement, state in statements:
        reject(sql, 'BEGIN; ' + statement + ' ROLLBACK;', state)
    require(sql("""BEGIN;
INSERT INTO restaurant_reviews_vector(restaurant_id,restaurant_name,city,review_text,updated_at)
 VALUES('fixture','fixture','fixture','synthetic','2000-01-01');
UPDATE restaurant_reviews_vector SET review_text='updated' WHERE restaurant_id='fixture';
SELECT updated_at>'2000-01-01' FROM restaurant_reviews_vector WHERE restaurant_id='fixture';
ROLLBACK;"""), 't', 'Update trigger did not run')
    # No actual login or global production role alterations: SET ROLE proves table
    # ACLs under a synthetic least-privilege role, both before and after pg_dump.
    require(sql('SET ROLE fixture_reader; SELECT count(*)>0 FROM products; RESET ROLE;'), 't', 'Read grant lost')
    reject(sql, "SET ROLE fixture_reader; UPDATE products SET price=1 WHERE product_code='AIRPODS-PRO';", '42501')
    reject(sql, 'SET ROLE fixture_reader; SELECT * FROM users;', '42501')
    return {'negativeConstraints': len(statements), 'updatedAtTrigger': True, 'readGrant': True,
            'writeAndUserReadDenied': True}


def prepare_recovery(sql):
    sql("""INSERT INTO users(id,username,password) OVERRIDING SYSTEM VALUE VALUES
 (93001,'fixture-erased','no-login-synthetic'),(93002,'fixture-survivor','no-login-synthetic');
INSERT INTO profile_lifecycle(user_id) VALUES(93001),(93002);
INSERT INTO user_profile_snapshot(user_id,schema_version,report) VALUES
 (93001,'fixture','{"preference":"old synthetic"}'),(93002,'fixture','{"preference":"keep"}');
INSERT INTO routing_call_log(user_id,user_input,route_method,llm_received_question) VALUES
 (93001,'original synthetic question','fixture','old synthetic derived'),
 (93002,'other synthetic question','fixture','keep derived');""")


def recovery_behavior(sql):
    """Use the shipped control replay against a restored consolidated DB."""
    import uuid
    from profile_control_recovery import replay_sql
    source = sql('SELECT source_id FROM profile_control_source;')
    event = dict(sequence=1, event_id=str(uuid.uuid4()), source_id=source,
                 user_id=93001, generation=1, analysis_enabled=False)
    replay = replay_sql(source, [event])
    reject(sql, replay.replace('UPDATE routing_call_log', 'SELECT 1/0; UPDATE routing_call_log'), '22012')
    require(sql('SELECT count(*) FROM user_profile_snapshot;'), '2', 'Partial erasure after failure')
    require(sql('SELECT count(*) FROM profile_control_outbox;'), '0', 'Partial controls after failure')
    sql(replay)
    sql(replay)
    require(sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=93001;'), '1|f', 'Tombstone not restored')
    require(sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=93001;'), '0', 'Erased profile reappeared')
    require(sql('SELECT count(*) FROM user_profile_snapshot WHERE user_id=93002;'), '1', 'Survivor erased')
    require(sql('SELECT count(*) FROM routing_call_log WHERE user_id=93001 AND user_input IS NOT NULL AND llm_received_question IS NULL;'), '1', 'Chat or derived-data contract')
    require(sql("SELECT count(*) FROM profile_cleanup_receipt WHERE state='PENDING';"), '5', 'Cross-store work falsely marked complete')
    require(sql('SELECT count(*) FROM profile_cleanup_job;'), '1', 'Replay duplicated cleanup job')
    reject(sql, replay_sql(source, [dict(event, event_id=str(uuid.uuid4()))]), 'P0001')
    return {'atomicRollback': True, 'idempotentReplay': True, 'erasedProfileAbsent': True,
            'survivorAndOriginalChatRetained': True, 'conflictingControlsRejected': True,
            'crossStoreReceiptsPending': 5, 'allStoresVerified': False}
