"""Experimental restore-control artifact contract, NOT a production export/restore tool.

Integrity comes from an independently trusted expected digest supplied by the caller.
A hash stored beside untrusted data is NOT authenticity, freshness or full coverage proof.
No DB/network/file I/O, keys, signatures, production erasure or traffic release here.
"""
import hashlib
import hmac
import json
import re
import uuid

MAX_BYTES = 1024 * 1024
FIELDS = {'schema', 'source_id', 'data_backup_sha256', 'captured_at', 'controls'}


def sha256(data): return hashlib.sha256(data).hexdigest()


def valid_digest(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def validate(value):
    if not isinstance(value, dict) or set(value) != FIELDS or type(value['schema']) is not int or value['schema'] != 1:
        raise ValueError('Invalid checkpoint schema')
    try:
        if not isinstance(value['source_id'], str) or str(uuid.UUID(value['source_id'])) != value['source_id']:
            raise ValueError()
    except (ValueError, AttributeError): raise ValueError('Invalid source identity')
    if not valid_digest(value['data_backup_sha256']): raise ValueError('Invalid backup binding')
    if type(value['captured_at']) is not int or not 0 < value['captured_at'] < 2**63:
        raise ValueError('Invalid capture timestamp')
    controls = value['controls']
    if not isinstance(controls, list) or not controls or len(controls) > 10000:
        raise ValueError('Nonempty bounded control inventory required')
    seen = set()
    for row in controls:
        if not isinstance(row, dict) or set(row) != {'user_id', 'generation', 'analysis_enabled'}:
            raise ValueError('Invalid control row')
        uid, gen, enabled = row['user_id'], row['generation'], row['analysis_enabled']
        if type(uid) is not int or not 0 < uid < 2**63 or uid in seen:
            raise ValueError('Invalid control identity')
        if type(gen) is not int or not 0 <= gen < 2**63 or type(enabled) is not bool or (not enabled and gen == 0):
            raise ValueError('Invalid lifecycle state')
        seen.add(uid)
    return value


def encode(source_id, backup_sha256, captured_at, controls):
    value = validate(dict(schema=1, source_id=source_id, data_backup_sha256=backup_sha256,
                          captured_at=captured_at, controls=controls))
    value = dict(value, controls=sorted(controls, key=lambda row: row['user_id']))
    data = json.dumps(value, sort_keys=True, separators=(',', ':')).encode('utf-8')
    if len(data) > MAX_BYTES: raise ValueError('Checkpoint too large')
    return data


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('Duplicate checkpoint key')
        result[key] = value
    return result


def verify(data, expected_sha256, expected_source_id, expected_backup_sha256, now, max_age_seconds):
    if not isinstance(data, bytes) or not 0 < len(data) <= MAX_BYTES:
        raise ValueError('Invalid checkpoint size')
    if not valid_digest(expected_sha256) or not hmac.compare_digest(sha256(data), expected_sha256):
        raise ValueError('Trusted checkpoint digest mismatch')
    if type(now) is not int or type(max_age_seconds) is not int or max_age_seconds <= 0:
        raise ValueError('Explicit restore freshness budget required')
    value = validate(json.loads(data.decode('utf-8'), object_pairs_hook=unique_object))
    if value['source_id'] != expected_source_id or value['data_backup_sha256'] != expected_backup_sha256:
        raise ValueError('Restore source or data backup mismatch')
    if not 0 <= now - value['captured_at'] <= max_age_seconds:
        raise ValueError('Stale or future checkpoint')
    return value


def paused_tombstones(value):
    """The existing synthetic barrier cannot safely handle reopen/rebuild semantics."""
    validate(value)
    if any(row['generation'] > 0 and row['analysis_enabled'] for row in value['controls']):
        raise ValueError('Reopened lifecycle needs a separate restore policy')
    return [{'user_id': row['user_id'], 'generation': row['generation']}
            for row in value['controls'] if not row['analysis_enabled']]
