"""Native memory-KB/Product knowledge subchain comparison, never automatic deployment.

Public synthetic manuals and fixed public ACL only, not production distribution,
PG SQL, catalog facts, Router, generation or Ragas four-metric judge calibration.
Families, not paraphrase counts, are the independent units for split summaries.
"""
import math
import statistics
from ragas_feedback import digest, text_hash
from retrieval_calibration import id_metrics

BASELINE = 'hybrid-semantic'
STRATEGIES = ('hybrid-semantic', 'dense-semantic', 'hybrid-blend035', 'hybrid-facet-rrf')
VISIBILITY = ('public', 'tenant', 'role', 'user', 'clearance')
METRICS = ('id_recall_at_k', 'id_precision_at_k', 'id_ndcg_at_k', 'id_ap_at_k')


def facets(query):
    import re
    parts = [part.strip() for part in re.split('[；;]', query)]
    return parts if 1 < len(parts) <= 3 and all(len(p) >= 4 for p in parts) else [query]


def validate_questions(data):
    if (data.get('schema_version') != 1 or data.get('synthetic_only') is not True
            or data.get('reference_policy') != 'curated_frozen_before_collection'
            or data.get('corpus') != 'synthetic_product_manuals_v1'
            or data.get('leaf_top_k') != 5 or data.get('strategies') != list(STRATEGIES)):
        raise ValueError('Frozen synthetic native profile required')
    documents, rows = data.get('documents'), data.get('cases')
    if not isinstance(documents, list) or not 2 <= len(documents) <= 128:
        raise ValueError('Bounded synthetic corpus required')
    docs = {}
    for row in documents:
        if (not isinstance(row, dict) or set(row) != {'id', 'family', 'knowledge_base', 'title', 'content', 'category', 'keywords', 'visibility'}
                or any(not isinstance(v, str) or not v.strip() or len(v) > 4000 for v in row.values())
                or row['knowledge_base'] not in ('product_knowledge', 'order_knowledge')
                or row['visibility'] not in VISIBILITY or row['id'] in docs
                or '[CID:' in row['title'] + row['content']):
            raise ValueError('Invalid synthetic document/ACL')
        docs[row['id']] = row
    if not isinstance(rows, list) or not 2 <= len(rows) <= 64:
        raise ValueError('Bounded frozen cases required')
    seen, texts = set(), set()
    families = {s: set() for s in ('development', 'holdout')}
    for row in rows:
        if (not isinstance(row, dict) or set(row) != {'id', 'split', 'family', 'kind', 'question', 'expected_doc_ids'}
                or any(not isinstance(row[k], str) or not row[k].strip() or len(row[k]) > 2000 for k in ('id', 'family', 'question'))
                or row['split'] not in families or row['kind'] not in ('named', 'paraphrase', 'multi_fact', 'acl_guard')):
            raise ValueError('Invalid frozen case')
        normalized = ''.join(row['question'].split()).casefold()
        if row['id'] in seen or normalized in texts:
            raise ValueError('Duplicate case/text')
        seen.add(row['id']); texts.add(normalized)
        gold = row['expected_doc_ids']
        if not isinstance(gold, list) or any(not isinstance(i, str) or i not in docs for i in gold) or len(set(gold)) != len(gold):
            raise ValueError('Independent gold IDs required')
        if row['kind'] == 'acl_guard':
            if gold or row['split'] != 'holdout': raise ValueError('Public guard cannot authorize private evidence')
        else:
            if not 1 <= len(gold) <= 5 or any(docs[i]['visibility'] != 'public'
                    or docs[i]['knowledge_base'] != 'product_knowledge' or docs[i]['family'] != row['family'] for i in gold):
                raise ValueError('Gold family/permission/scope mismatch')
            families[row['split']].add(row['family'])
    if not all(families.values()) or families['development'] & families['holdout']:
        raise ValueError('Reference-family leakage between splits')
    return rows, docs


def probe_request(data):
    rows, _ = validate_questions(data)
    # Entire corpus is indexed, but references/families/split/kind never enter retrieval.
    return {'queries': [{k: row[k] for k in ('id', 'question')} for row in rows],
            'documents': [{k: v for k, v in row.items() if k != 'family'} for row in data['documents']]}


def validate_trial(data, trial):
    rows, docs = validate_questions(data)
    if (trial.get('schema_version') != 1 or trial.get('backend') != 'native-memory-product-knowledge-subchain'
            or trial.get('embedding_verified') is not True or trial.get('leaf_top_k') != 5):
        raise ValueError('Actual native embedding probe required')
    if any(type(trial.get(k)) is not int or trial[k] <= 0 for k in ('embedding_dimension', 'embedding_calls')):
        raise ValueError('Embedding verification missing')
    expected = {d: [] for d in ('order_knowledge', 'product_knowledge')}
    for row in sorted(docs.values(), key=lambda r: r['id']):
        expected[row['knowledge_base']].append({'id': row['id'], 'textSha256': text_hash(row['title'] + '。\n' + row['content'] + '\n关键词：' + row['keywords'])})
    if trial.get('corpus') != expected: raise ValueError('Corpus content/identity drift')
    found = trial.get('results')
    if not isinstance(found, list) or len(found) != len(rows): raise ValueError('Missing cases')
    actual = {r['id']: r for r in found}
    if len(actual) != len(found) or set(actual) != {r['id'] for r in rows}: raise ValueError('Case identity drift')
    allowed = {i for i, r in docs.items() if r['visibility'] == 'public' and r['knowledge_base'] == 'product_knowledge'}
    for row in rows:
        result = actual[row['id']]
        if result.get('question_sha256') != text_hash(row['question']): raise ValueError('Question drift')
        ranks = result.get('rankings')
        if not isinstance(ranks, dict) or set(ranks) != set(STRATEGIES): raise ValueError('Fixed grid drift')
        for strategy, rank in ranks.items():
            ids = rank.get('doc_ids')
            if (not isinstance(ids, list) or len(ids) > 5 or any(not isinstance(i, str) for i in ids)
                    or len(set(ids)) != len(ids) or not set(ids) <= allowed):
                raise ValueError('ACL/scope/ranking violation')
            if rank.get('context_doc_ids') != ids: raise ValueError('Leaf/context identity mismatch')
            if (type(rank.get('fragments')) is not int or rank['fragments'] != int(bool(ids))
                    or type(rank.get('kb_service_calls')) is not int or rank['kb_service_calls'] != 1 or rank.get('degraded') is not False
                    or rank.get('selected_domains') != ['product_knowledge'] or rank.get('scope_reason') != 'product-agent-capability'):
                raise ValueError('Native subchain/single-attempt contract drift')
            pieces = facets(row['question']) if strategy == 'hybrid-facet-rrf' else [row['question']]
            if (type(rank.get('facet_count')) is not int or rank['facet_count'] != len(pieces)
                    or type(rank.get('native_query_count')) is not int or rank['native_query_count'] != len(pieces)
                    or rank.get('facet_sha256') != [text_hash(q) for q in pieces]):
                raise ValueError('Unfrozen/gold-derived rewrite')
            native_ranks = rank.get('native_rankings')
            if not isinstance(native_ranks, list) or len(native_ranks) != len(pieces):
                raise ValueError('Actual per-query native rankings required')
            for query, found in zip(pieces, native_ranks):
                limit = 10 if len(pieces) > 1 else 5
                candidates = found.get('doc_ids')
                if (found.get('question_sha256') != text_hash(query) or found.get('top_k') != limit
                        or not isinstance(candidates, list) or len(candidates) > limit
                        or any(not isinstance(i, str) for i in candidates) or len(set(candidates)) != len(candidates)
                        or not set(candidates) <= allowed):
                    raise ValueError('Native per-query trace/ACL drift')
            sparse, dense, blend = (rank.get(k) for k in ('sparse_weight', 'dense_weight', 'fusion_weight'))
            if (any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 <= v <= 1 for v in (sparse, dense, blend))
                    or abs(sparse + dense - 1) > 1e-9 or blend != (.35 if strategy == 'hybrid-blend035' else 0)):
                raise ValueError('Actual weight drift')
            sha = rank.get('context_sha256', '')
            if not isinstance(sha, str) or len(sha) != 64 or any(c not in '0123456789abcdef' for c in sha):
                raise ValueError('Context fingerprint missing')
            if type(rank.get('context_chars')) is not int or not 0 <= rank['context_chars'] <= 30000:
                raise ValueError('Bounded context required')
    return actual


def evaluate(data, trials):
    rows, docs = validate_questions(data)
    if not isinstance(trials, list) or not 3 <= len(trials) <= 5: raise ValueError('3..5 actual trials required')
    maps = [validate_trial(data, t) for t in trials]
    stable = len({digest(t['results']) for t in trials}) == 1
    positive = [r for r in rows if r['kind'] != 'acl_guard']
    scores = {r['id']: {s: {m: statistics.median(id_metrics(t[r['id']]['rankings'][s]['doc_ids'],r['expected_doc_ids'],5)[m]
                 for t in maps) for m in METRICS} for s in STRATEGIES} for r in positive}
    summary = {split: {} for split in ('development', 'holdout')}
    family_counts = {}
    for split in summary:
        groups = sorted({r['family'] for r in positive if r['split'] == split}); family_counts[split] = len(groups)
        for s in STRATEGIES:
            summary[split][s] = {m: statistics.mean(statistics.mean(scores[r['id']][s][m]
                 for r in positive if r['family'] == group) for group in groups) for m in METRICS}
    development = [r for r in positive if r['split'] == 'development']
    eligible = [s for s in STRATEGIES if all(scores[r['id']][s]['id_recall_at_k'] + 1e-9 >= scores[r['id']][BASELINE]['id_recall_at_k'] for r in development)]
    selected = max(eligible, key=lambda s: summary['development'][s]['id_ndcg_at_k'])
    holdout = [r for r in positive if r['split'] == 'holdout']
    regression = [r['id'] for r in holdout if any(scores[r['id']][selected][m] + 1e-9 < scores[r['id']][BASELINE][m]
                 for m in ('id_recall_at_k', 'id_ndcg_at_k'))]
    gain = summary['holdout'][selected]['id_ndcg_at_k'] - summary['holdout'][BASELINE]['id_ndcg_at_k']
    reasons = ['SYNTHETIC_MEMORY_SUBCHAIN_NOT_PRODUCTION_DISTRIBUTION', 'REFERENCE_NOT_EXPERT_REVIEWED']
    if family_counts['holdout'] < 20: reasons.append('INSUFFICIENT_INDEPENDENT_HOLDOUT_FAMILIES')
    if selected == BASELINE or gain < .02: reasons.append('NO_CLEAR_DEVELOPMENT_SELECTED_GAIN')
    if regression: reasons.append('HOLDOUT_CASE_REGRESSION')
    if not stable: reasons.append('UNSTABLE_RETRIEVAL')
    return {'schema_version':1, 'synthetic_only':True, 'experiment_scope':'native_memory_product_knowledge_subchain',
            'question_set_sha256':digest(data),'corpus_sha256':digest(trials[0]['corpus']),
            'trial_sha256':[digest(t) for t in trials],'repeats':len(trials),'stable_rankings':stable,
            'baseline':BASELINE,'selected_on_development':selected,'selection_uses_holdout':False,
            'split_counts':{split:sum(r['split']==split and r['kind']!='acl_guard' for r in rows) for split in summary},
            'independent_family_counts':family_counts,'guard_case_count':sum(r['kind']=='acl_guard' for r in rows),
            'acl_scope_violations':0,'summary':summary,'case_scores':scores,'holdout_ndcg_gain':gain,
            'holdout_regression_ids':regression,'decision':'KEEP_PRODUCTION_UNCHANGED','reasons':reasons,
            'granularity':{'leaf_top_k':5,'final_fragment_max':1,'metric_unit':'document_id_not_aggregate_fragment'},
            'outer_blend_changes_leaf_rankings':any(t[r['id']]['rankings']['hybrid-semantic']['doc_ids'] != t[r['id']]['rankings']['hybrid-blend035']['doc_ids'] for r in rows for t in maps),
            'facet_changes':[{ 'id':r['id'],'baseline_ids':maps[0][r['id']]['rankings'][BASELINE]['doc_ids'],
                              'facet_ids':maps[0][r['id']]['rankings']['hybrid-facet-rrf']['doc_ids'] }
                              for r in positive if maps[0][r['id']]['rankings'][BASELINE]['doc_ids'] != maps[0][r['id']]['rankings']['hybrid-facet-rrf']['doc_ids']],
            'facet_lost_gold':[{ 'id':r['id'], 'lost_doc_ids':sorted(set(r['expected_doc_ids'])
                              & set(maps[0][r['id']]['rankings'][BASELINE]['doc_ids'])
                              - set(maps[0][r['id']]['rankings']['hybrid-facet-rrf']['doc_ids'])),
                              'native_rankings':maps[0][r['id']]['rankings']['hybrid-facet-rrf']['native_rankings'] }
                              for r in positive if set(r['expected_doc_ids']) & set(maps[0][r['id']]['rankings'][BASELINE]['doc_ids'])
                              - set(maps[0][r['id']]['rankings']['hybrid-facet-rrf']['doc_ids'])],
            'ragas_scores_computed':False,'parameters_applied':False,'online_retries_executed':0,'business_writes':0}
