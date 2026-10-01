"""Build an explicitly scoped class overlay against the hash-pinned deployed baseline.
No source credentials, model configuration, libraries or unrelated resources are included.
"""
import argparse, hashlib, json, zipfile
from pathlib import Path

BASELINE = '1d9a04ea419f44c92542f2003810408088c5ceb35d0bdd930ae6ec15b1ddf9e2'
OLD = ('config/NativeRagProperties.class', 'service/search/ProductRagService.class',
       'service/search/ProductRetrievalDiagnostics.class', 'service/search/handler/KnowledgeSearchHandler.class')
NEW = ('service/search/BoundedRetrievalFeedback.class', 'service/search/BoundedRetrievalFeedback$Decision.class',
       'service/search/EvidenceGapEvaluator.class', 'service/search/EvidenceGapEvaluator$Assessment.class',
       'service/search/EvidenceGapEvaluator$Chunk.class')
PREFIX = 'com/example/smartassistant/'

def main():
    p=argparse.ArgumentParser(); p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--classes',type=Path,required=True); p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if hashlib.sha256(a.baseline.read_bytes()).hexdigest()!=BASELINE: raise ValueError('Baseline drift')
    a.output.mkdir(exist_ok=False)
    guard={}
    with zipfile.ZipFile(a.baseline) as old, zipfile.ZipFile(a.output/'replacement.zip','x',zipfile.ZIP_DEFLATED) as patch:
        for name in OLD+NEW:
            entry='BOOT-INF/classes/'+PREFIX+name
            if name in OLD: guard[entry]=hashlib.sha256(old.read(entry)).hexdigest()
            elif entry in old.namelist(): raise ValueError('New class already exists')
            patch.writestr(entry,(a.classes/PREFIX/name).read_bytes())
    (a.output/'guard.json').write_text(json.dumps({'baseline':BASELINE,'replace':guard,
        'add':['BOOT-INF/classes/'+PREFIX+name for name in NEW]},indent=2),encoding='utf-8')
    print(json.dumps({'replacementClasses':len(OLD),'newClasses':len(NEW)}))
if __name__=='__main__': main()
