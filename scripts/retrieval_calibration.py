"""Frozen same-gold, development/holdout ranking experiment, never an online retry.

Uses real JVM retrieval outputs. ID metrics are NOT Ragas scores, answer quality,
or production recall. Development alone selects one candidate; holdout cannot
select another weight. Provisional readiness checks never deploy a parameter.
"""
import argparse
import json
import math
import statistics
from pathlib import Path

from ragas_feedback import digest, text_hash, write_new

BASELINE = 'adaptive-semantic'
STRATEGIES = tuple(setting + suffix for setting in
                   ('adaptive', 'sparse-020', 'sparse-050', 'sparse-080')
                   for suffix in ('-semantic', '-blend035'))
METRICS = ('id_recall_at_k', 'id_precision_at_k', 'id_ndcg_at_k', 'id_ap_at_k')


def validate_questions(dataset):
    if (dataset.get('schema_version') != 1 or dataset.get('synthetic_only') is not True
            or dataset.get('reference_policy') != 'curated_frozen_before_collection'
            or dataset.get('corpus') != 'public_knowledge_seed'):
        raise ValueError('Frozen synthetic seed reference required')
    k = dataset.get('top_k')
    if isinstance(k, bool) or not isinstance(k, int) or not 1 <= k <= 8:
        raise ValueError('Bounded top-k required')
    rows = dataset.get('cases')
    if not isinstance(rows, list) or not 2 <= len(rows) <= 64:
        raise ValueError('Expected 2..64 frozen cases')
    seen, texts = set(), set()
    groups = {split: set() for split in ('development', 'holdout')}
    gold = {split: set() for split in groups}
    for row in rows:
        if set(row) != {'id', 'split', 'family', 'knowledge_base', 'question', 'expected_doc_ids'}:
            raise ValueError('Unexpected frozen case fields')
        if row['split'] not in groups or row['knowledge_base'] not in ('order_knowledge', 'product_knowledge'):
            raise ValueError('Unknown split/domain')
        if any(not isinstance(row[key], str) or not row[key].strip() or len(row[key]) > 2000
               for key in ('id', 'family', 'question')):
            raise ValueError('Invalid case identity/text')
        normalized = ''.join(row['question'].split()).casefold()
        if row['id'] in seen or normalized in texts:
            raise ValueError('Duplicate case/question')
        seen.add(row['id']); texts.add(normalized)
        ids = row['expected_doc_ids']
        if (not isinstance(ids, list) or not 1 <= len(ids) <= 8
                or not all(isinstance(item, str) and item.strip() for item in ids)
                or len(set(ids)) != len(ids)):
            raise ValueError('Independent relevant document IDs required')
        groups[row['split']].add(row['family'])
        gold[row['split']].update((row['knowledge_base'], identifier) for identifier in ids)
    if not all(groups.values()) or groups['development'] & groups['holdout'] or gold['development'] & gold['holdout']:
        raise ValueError('Family and gold-document leakage between splits')
    return rows


def probe_request(dataset):
    rows = validate_questions(dataset)
    # No split, relevance labels or reference answer is sent to the retriever.
    return {'top_k': dataset['top_k'], 'queries': [
        {key: row[key] for key in ('id', 'question', 'knowledge_base')} for row in rows]}


def validate_trial(dataset, trial):
    rows = validate_questions(dataset)
    if (trial.get('schema_version') != 1 or trial.get('backend') != 'public-seed-bm25-bge-product-rrf-rerank'
            or trial.get('embedding_verified') is not True or trial.get('top_k') != dataset['top_k']):
        raise ValueError('Actual bounded embedding-backed probe required')
    for key in ('embedding_dimension', 'embedding_calls'):
        if type(trial.get(key)) is not int or trial[key] < 1:
            raise ValueError('Missing embedding verification')
    corpus = trial.get('corpus')
    if not isinstance(corpus, dict) or set(corpus) != {'order_knowledge', 'product_knowledge'}:
        raise ValueError('Pinned seed domain corpus required')
    corpus_ids = {}
    for domain, documents in corpus.items():
        if not isinstance(documents, list) or not documents:
            raise ValueError('Empty corpus')
        ids = []
        for doc in documents:
            sha = doc.get('textSha256', '') if isinstance(doc, dict) else ''
            if (not isinstance(doc, dict) or not isinstance(doc.get('id'), str) or not doc['id']
                    or not isinstance(sha, str) or len(sha) != 64 or any(c not in '0123456789abcdef' for c in sha)):
                raise ValueError('Invalid corpus fingerprint')
            ids.append(doc['id'])
        if len(set(ids)) != len(ids):
            raise ValueError('Duplicate corpus ID')
        corpus_ids[domain] = set(ids)
    results = trial.get('results')
    if not isinstance(results, list) or len(results) != len(rows):
        raise ValueError('Missing experiment cases')
    actual = {row['id']: row for row in results}
    if len(actual) != len(results) or set(actual) != {row['id'] for row in rows}:
        raise ValueError('Case identity drift')
    for row in rows:
        found = actual[row['id']]
        if (found.get('question_sha256') != text_hash(row['question']) or found.get('knowledge_base') != row['knowledge_base']
                or not set(row['expected_doc_ids']) <= corpus_ids[row['knowledge_base']]):
            raise ValueError('Domain or gold corpus drift')
        rankings = found.get('rankings')
        if not isinstance(rankings, dict) or set(rankings) != set(STRATEGIES):
            raise ValueError('Incomplete fixed strategy grid')
        for strategy, ranking in rankings.items():
            pool, ids = ranking.get('candidate_ids'), ranking.get('doc_ids')
            if (not isinstance(pool, list) or not isinstance(ids, list) or len(pool) > 20 or len(ids) > dataset['top_k']
                    or not all(isinstance(item, str) for item in pool + ids)
                    or len(set(pool)) != len(pool) or len(set(ids)) != len(ids)
                    or not set(ids) <= set(pool) or not set(pool) <= corpus_ids[row['knowledge_base']]):
                raise ValueError('Invalid ranking/pool IDs')
            sparse, dense, blend = (ranking.get(key) for key in ('sparse_weight', 'dense_weight', 'fusion_weight'))
            if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 <= v <= 1 for v in (sparse,dense,blend)):
                raise ValueError('Invalid actual weights')
            if abs(sparse + dense - 1) > 1e-9 or abs(blend - (.35 if strategy.endswith('blend035') else 0)) > 1e-9:
                raise ValueError('Weight contract drift')
            if not strategy.startswith('adaptive') and abs(sparse - int(strategy.split('-')[1]) / 100) > 1e-9:
                raise ValueError('Sparse grid drift')
    return actual


def id_metrics(ids, gold_ids, k):
    gold = set(gold_ids)
    hits = [identifier in gold for identifier in ids[:k]]
    count = sum(hits)
    dcg = sum(hit / math.log2(index + 2) for index, hit in enumerate(hits))
    ideal = sum(1 / math.log2(index + 2) for index in range(min(k, len(gold))))
    ap = sum(sum(hits[:index+1]) / (index+1) for index, hit in enumerate(hits) if hit)
    return {'id_recall_at_k': count / len(gold), 'id_precision_at_k': count / k,
            'id_ndcg_at_k': dcg / ideal, 'id_ap_at_k': ap / min(k, len(gold))}


def evaluate(dataset, trials):
    rows = validate_questions(dataset)
    if not isinstance(trials, list) or not 3 <= len(trials) <= 5:
        raise ValueError('3..5 independent probe executions required')
    maps = [validate_trial(dataset, trial) for trial in trials]
    if len({digest(trial['corpus']) for trial in trials}) != 1:
        raise ValueError('Corpus drift across executions')
    # Repeat the retriever, not just metric arithmetic, to detect unstable rankings.
    stable = len({digest(trial['results']) for trial in trials}) == 1
    summary = {split: {} for split in ('development', 'holdout')}
    case_scores = {}
    for row in rows:
        case_scores[row['id']] = {}
        for strategy in STRATEGIES:
            runs = [id_metrics(m[row['id']]['rankings'][strategy]['doc_ids'], row['expected_doc_ids'],dataset['top_k']) for m in maps]
            case_scores[row['id']][strategy] = {metric: statistics.median(run[metric] for run in runs) for metric in METRICS}
    for split in summary:
        split_rows = [row for row in rows if row['split'] == split]
        for strategy in STRATEGIES:
            summary[split][strategy] = {metric: statistics.mean(case_scores[row['id']][strategy][metric] for row in split_rows) for metric in METRICS}
    development = [row for row in rows if row['split'] == 'development']
    eligible = [s for s in STRATEGIES if all(case_scores[row['id']][s]['id_recall_at_k'] + 1e-9 >= case_scores[row['id']][BASELINE]['id_recall_at_k'] for row in development)]
    # Fixed grid order breaks ties in favor of baseline; holdout is never consulted.
    selected = max(eligible, key=lambda s: summary['development'][s]['id_ndcg_at_k'])
    holdout = [row for row in rows if row['split'] == 'holdout']
    regressions = [row['id'] for row in holdout if any(case_scores[row['id']][selected][metric] + 1e-9 < case_scores[row['id']][BASELINE][metric]
        for metric in ('id_recall_at_k','id_ndcg_at_k'))]
    gain = summary['holdout'][selected]['id_ndcg_at_k'] - summary['holdout'][BASELINE]['id_ndcg_at_k']
    reasons = []
    if not stable: reasons.append('UNSTABLE_RETRIEVAL')
    if len(holdout) < 20: reasons.append('INSUFFICIENT_HOLDOUT_CASES')
    if selected == BASELINE or gain < .02: reasons.append('NO_CLEAR_HOLDOUT_GAIN')
    if regressions: reasons.append('HOLDOUT_REGRESSION')
    reasons.append('SEED_CORPUS_NOT_PRODUCTION_DISTRIBUTION')
    semantic_strategies = [strategy for strategy in STRATEGIES if strategy.endswith('-semantic')]
    weights_erased_on_fixed_pool = all(len({tuple(maps[0][row['id']]['rankings'][strategy]['doc_ids'])
        for strategy in semantic_strategies}) == 1 for row in rows)
    return {'schema_version':1, 'synthetic_only':True,'experiment_scope':'public_seed_component_benchmark',
            'question_set_sha256':digest(dataset),'corpus_sha256':digest(trials[0]['corpus']),
            'trial_sha256':[digest(trial) for trial in trials], 'repeats':len(trials), 'stable_rankings':stable,
            'baseline':BASELINE,'selected_on_development':selected,'selection_uses_holdout':False,
            'split_counts':{split:sum(row['split']==split for row in rows) for split in summary},
            'summary':summary,'case_scores':case_scores,'holdout_ndcg_gain':gain,
            'semantic_only_rankings_identical_across_sparse_weights':weights_erased_on_fixed_pool,
            'holdout_regression_ids':regressions,'decision':'KEEP_PRODUCTION_UNCHANGED','reasons':reasons,
            'provisional_review_thresholds':{'minimum_holdout_cases':20,'minimum_ndcg_gain':.02},
            'thresholds_calibrated':False,'ragas_scores_computed':False,'online_retries_executed':0,
            'parameters_applied':False,'business_writes':0}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--questions',required=True,type=Path)
    parser.add_argument('--trials',required=True,type=Path,nargs='+')
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    dataset=json.loads(args.questions.read_text(encoding='utf-8'))
    report=evaluate(dataset,[json.loads(path.read_text(encoding='utf-8')) for path in args.trials])
    write_new(args.output,report)
    print(json.dumps({key:report[key] for key in ('decision','selected_on_development','split_counts','stable_rankings','holdout_ndcg_gain','reasons')}))


if __name__=='__main__': main()
