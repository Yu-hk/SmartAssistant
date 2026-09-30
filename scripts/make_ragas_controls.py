"""Create labeled negative controls from a synthetic captured dataset, offline."""
import argparse
import copy
import json
from pathlib import Path
from ragas_feedback import digest, validate_dataset, write_new


def controls(dataset):
    rows = validate_dataset(dataset)
    single = next(row for row in rows if row['id'] == 'price-single')
    multiple = next(row for row in rows if row['id'] == 'price-multiple')
    if '1999' not in single['response']:
        raise ValueError('Frozen control price no longer matches source')
    wrong = copy.deepcopy(single)
    wrong.update(id='control-wrong-price', source='negative_control',
                 response=single['response'].replace('1999', '999'))
    unrelated = copy.deepcopy(single)
    unrelated.update(id='control-irrelevant-answer', source='negative_control', response='MacBook Air M3售价8999元。')
    missing = copy.deepcopy(multiple)
    missing.update(id='control-missing-evidence', source='negative_control',
                   retrieved_contexts=multiple['retrieved_contexts'][1:],
                   retrieved_context_ids=multiple['retrieved_context_ids'][1:])
    for row in (wrong, unrelated, missing):
        # Modified controls are not live responses. Never retain a manifest bound
        # to the old answer/context or use its complete-coverage claim as current.
        trace = row.pop('evidence_trace', None)
        if trace is not None:
            row['source_trace_sha256'] = digest(trace)
    result = {'schema_version': 1, 'synthetic_only': True, 'source_dataset_sha256': digest(dataset),
              'context_kind': 'intentionally_modified_negative_controls',
              'cases': [wrong, unrelated, missing]}
    validate_dataset(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    write_new(args.output, controls(json.loads(args.dataset.read_text(encoding='utf-8'))))


if __name__ == '__main__': main()
