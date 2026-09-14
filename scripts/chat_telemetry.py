"""Inspect completed SSE traces without mistaking a node snapshot for final usage."""
import json


def parse_events(raw):
    events = []
    for block in raw.replace('\r\n', '\n').split('\n\n'):
        data = '\n'.join(line[5:].lstrip() for line in block.splitlines() if line.startswith('data:'))
        if not data:
            continue
        event = json.loads(data)
        if not isinstance(event, dict):
            continue
        nested = event.get('data')
        if isinstance(nested, dict):
            event = dict(nested, type=event.get('type', nested.get('type')))
        events.append(event)
    return events


def final_usage(events):
    """Require response -> final token_usage -> done; never infer completeness from counts."""
    done = next((i for i, event in enumerate(events) if event.get('type') == 'done'), None)
    if done is None:
        raise ValueError('SSE stream has not completed')
    responses = [i for i, event in enumerate(events[:done]) if event.get('type') == 'response']
    if not responses:
        raise ValueError('No final response before done')
    candidates = [event for event in events[responses[-1] + 1:done] if event.get('type') == 'token_usage']
    if not candidates:
        raise ValueError('No terminal token usage; earlier node snapshots are not final totals')
    usage = candidates[-1]
    counts = {key: usage[key] for key in ('promptTokens', 'completionTokens', 'totalTokens') if key in usage}
    for value in counts.values():
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError('Invalid measured token count')
    complete = usage.get('tokenUsageComplete') is True
    if complete and 'totalTokens' not in counts:
        raise ValueError('Complete usage is missing totalTokens')
    return dict(counts, tokenUsageComplete=complete)
