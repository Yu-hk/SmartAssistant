"""Synthetic PostgreSQL control-outbox checks. No production connector or exporter.

Called only inside profile_restore_drill's unnetworked disposable container.
The same-database outbox is NOT independent storage and is NOT a durable ACK.
"""
import time


def exercise(sql, start):
    """sql runs in the fixture DB; start creates a second psql connection there."""
    sql('INSERT INTO users VALUES(92001),(92002); INSERT INTO profile_lifecycle(user_id) VALUES(92001),(92002);')
    assert sql('SELECT last_sequence FROM profile_control_stream') == '2'
    assert sql('SELECT count(*) FROM profile_control_event') == '2'
    sql('BEGIN; DELETE FROM profile_control_stream; UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=92001; COMMIT;', success=False)
    assert sql('SELECT generation FROM profile_lifecycle WHERE user_id=92001') == '0'
    assert sql('SELECT last_sequence FROM profile_control_stream') == '2'
    sql('BEGIN; UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=92001; SELECT 1/0; COMMIT;', success=False)
    assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=92001') == '0|t'
    assert sql('SELECT last_sequence FROM profile_control_stream') == '2'
    assert sql('SELECT count(*) FROM profile_control_event') == '2'
    # Make event persistence fail after counter allocation: lifecycle + counter roll back too.
    sql("ALTER TABLE profile_control_event ADD CONSTRAINT fixture_fail CHECK(user_id<>92001 OR generation=0);")
    sql('UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=92001;', success=False)
    assert sql('SELECT generation FROM profile_lifecycle WHERE user_id=92001') == '0'
    assert sql('SELECT last_sequence FROM profile_control_stream') == '2'
    sql('ALTER TABLE profile_control_event DROP CONSTRAINT fixture_fail;')
    sql('UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=92001;')
    sql('UPDATE profile_lifecycle SET generation=1,analysis_enabled=false,updated_at=CURRENT_TIMESTAMP WHERE user_id=92001;')
    sql('INSERT INTO profile_lifecycle(user_id) VALUES(92001) ON CONFLICT DO NOTHING;')
    assert sql('SELECT last_sequence FROM profile_control_stream') == '3'
    sql('UPDATE profile_lifecycle SET analysis_enabled=true WHERE user_id=92001;', success=False)
    sql('UPDATE profile_lifecycle SET generation=0 WHERE user_id=92001;', success=False)
    sql('UPDATE profile_lifecycle SET generation=3 WHERE user_id=92001;', success=False)
    sql('UPDATE profile_lifecycle SET user_id=92999 WHERE user_id=92001;', success=False)
    sql('DELETE FROM profile_lifecycle WHERE user_id=92001;', success=False)
    sql('UPDATE profile_lifecycle SET generation=2,analysis_enabled=true WHERE user_id=92001;')
    assert sql('SELECT generation,analysis_enabled FROM profile_control_event WHERE sequence=4') == '2|t'
    # A holds the global counter lock; B, changing another user, must not allocate
    # past A's uncommitted event. Synchronize on pg_stat_activity, not sleep timing.
    processes = []
    try:
        a = start("SET application_name='profile_outbox_a'; BEGIN; SET LOCAL statement_timeout='15s'; "
                  "SET LOCAL idle_in_transaction_session_timeout='30s'; "
                  "UPDATE profile_lifecycle SET generation=3,analysis_enabled=false WHERE user_id=92001;", hold=True)
        processes.append(a)
        await_activity(sql, "application_name='profile_outbox_a' AND state='idle in transaction' AND query LIKE '%user_id=92001%'")
        assert sql('SELECT last_sequence FROM profile_control_stream') == '4'
        b = start("SET application_name='profile_outbox_b'; BEGIN; SET LOCAL statement_timeout='15s'; "
                  "UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=92002; COMMIT;")
        processes.append(b)
        await_activity(sql, "application_name='profile_outbox_b' AND wait_event_type='Lock'")
        assert sql('SELECT count(*) FROM profile_control_event') == '4'
        # Release A only after B is observed waiting: no timing-dependent sleep.
        a.stdin.write(b'ROLLBACK;\n')
        a.stdin.close()
        a.stdin = None
        a.communicate(timeout=20)
        b.communicate(timeout=20)
        assert a.returncode == 0 and b.returncode == 0
    finally:
        for process in processes:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)
    assert sql('SELECT last_sequence FROM profile_control_stream') == '5'
    assert sql('SELECT user_id,generation FROM profile_control_event WHERE sequence=5') == '92002|1'
    assert sql('SELECT generation,analysis_enabled FROM profile_lifecycle WHERE user_id=92001') == '2|t'
    assert sql('SELECT count(*),count(DISTINCT event_id),count(DISTINCT source_id),min(sequence),max(sequence) FROM profile_control_event') == '5|5|1|1|5'
    return ['control-and-event-atomic-rollback', 'event-write-failure-rolls-back-control',
            'duplicate-state-no-extra-event', 'reopen-needs-new-generation',
            'invalid-transition-and-control-deletion-rejected', 'concurrent-counter-commit-order',
            'rollback-no-watermark-hole', 'minimal-event-identity-and-source']


def await_activity(sql, predicate):
    for unused in range(60):
        if sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND " + predicate) == '1':
            return
        time.sleep(0.1)
    raise AssertionError('Fixture connection did not reach expected synchronization point')
