"""Build the exact evaluated overlay against live Product, saving a private non-secret candidate manifest."""
import hashlib, json, subprocess
from pathlib import Path
from apply_bounded_feedback_overlay import apply
from ragas_feedback import write_new
ROOT=Path('/opt/smart-assistant/releases/bounded-feedback-20261001')
def main():
    if Path(__file__).resolve().parent!=ROOT: raise ValueError('Confined release root required')
    mounts=json.loads(subprocess.check_output(['docker','inspect','--format','{{json .Mounts}}','smart-product'],timeout=30))
    source=Path(next(m['Source'] for m in mounts if m['Destination']=='/app/app.jar'))
    report=json.loads((ROOT/'experiment.json').read_text())
    if report.get('promotable') is not True or hashlib.sha256((ROOT/'replacement.zip').read_bytes()).hexdigest()!=report['replacement_sha256']:
        raise ValueError('Evaluated package required')
    result=apply(source,ROOT,ROOT/'smart-assistant-product-scoped.jar')
    result['replacement_sha256']=report['replacement_sha256']
    write_new(ROOT/'candidate.json',result); print(json.dumps(result))
if __name__=='__main__': main()
