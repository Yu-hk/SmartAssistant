"""Run the offline judge in an isolated short-lived container; no service restart.

Run from a new /opt/smart-assistant/eval/ragas-feedback-* directory. Provision
dependencies separately with pip into deps/. Only its output directory is RW.
Configured key stays in process/container memory, never a saved env file.
"""
import argparse
import ipaddress
import json
import os
import re
import subprocess
from pathlib import Path


def container_command(root, image, output, dataset, ip, repeats, max_tokens=4096, case_id=None, thinking='disabled'):
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image) or not 1 <= repeats <= 10:
        raise ValueError('Pinned image and bounded repeats required')
    if Path(output).name != output or not output.endswith('.json') or dataset not in ('dataset.json', 'controls.json'):
        raise ValueError('Confined input/output names required')
    if not ipaddress.ip_address(ip).is_private:
        raise ValueError('Private embedding endpoint required')
    if max_tokens not in (1024, 4096) or thinking not in ('disabled', 'enabled') or (case_id is not None and not re.fullmatch(r'[a-z0-9-]{1,64}', case_id)):
        raise ValueError('Bounded judge tokens and synthetic case ID required')
    command = ['docker', 'run', '--rm', '--name', root.name + '-judge',
            '--network', 'smart-network', '--cpus', '1', '--memory', '2g', '--pids-limit', '256',
            '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
            '--tmpfs', '/tmp:rw,nosuid,noexec,size=134217728',
            '-v', str(root) + ':/eval:ro', '-v', str(root / 'outputs') + ':/outputs:rw',
            '-e', 'PYTHONPATH=/eval/deps', '-e', 'PYTHONDONTWRITEBYTECODE=1',
            '-e', 'RAGAS_DO_NOT_TRACK=true', '-e', 'DEEPSEEK_API_KEY',
            image, 'python', '/eval/ragas_feedback.py',
            '--dataset', '/eval/' + dataset, '--output', '/outputs/' + output,
            '--embedding-url', 'http://' + ip + ':8091', '--repeats', str(repeats),
            '--judge-max-tokens', str(max_tokens), '--judge-thinking', thinking]
    if case_id is not None:
        command += ['--case-id', case_id]
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='Pinned local image ID')
    parser.add_argument('--output-name', required=True)
    parser.add_argument('--dataset-name', choices=('dataset.json', 'controls.json'), default='dataset.json')
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--judge-max-tokens', type=int, choices=(1024, 4096), default=4096)
    parser.add_argument('--judge-thinking', choices=('disabled', 'enabled'), default='disabled')
    parser.add_argument('--case-id')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    if root.parent != Path('/opt/smart-assistant/eval') or not root.name.startswith('ragas-feedback-'):
        raise ValueError('Dedicated evaluation directory required')
    if not (root / 'deps').is_dir() or not (root / 'outputs').is_dir():
        raise ValueError('Isolated dependencies and outputs must be prepared')
    if Path(args.output_name).name != args.output_name or not args.output_name.endswith('.json') or (root / 'outputs' / args.output_name).exists():
        raise ValueError('Fresh report basename required')
    if not 1 <= args.repeats <= 10 or not re.fullmatch(r'sha256:[a-f0-9]{64}', args.image):
        raise ValueError('Pinned image and bounded repeats required')
    source_env = json.loads(subprocess.check_output([
        'docker', 'inspect', '--format', '{{json .Config.Env}}', 'smart-consumer'], timeout=30))
    entries = [entry.split('=', 1)[1] for entry in source_env if entry.startswith('DEEPSEEK_API_KEY=')]
    if len(entries) != 1 or not entries[0].strip():
        raise ValueError('Configured judge key unavailable')
    env = {**os.environ, 'DEEPSEEK_API_KEY': entries[0]}
    ip = subprocess.check_output(['docker', 'inspect', '--format',
                                 '{{(index .NetworkSettings.Networks "smart-network").IPAddress}}',
                                 'smart-embedding-service'], timeout=30).decode().strip()
    # Root/code/deps are read-only. No Docker socket, business volume, database
    # credential or host namespace is provided to the model-evaluation process.
    command = container_command(root, args.image, args.output_name, args.dataset_name, ip, args.repeats,
                                args.judge_max_tokens, args.case_id, args.judge_thinking)
    try:
        result = subprocess.run(command, env=env, timeout=14400)
    except subprocess.TimeoutExpired:
        # Stop only our uniquely named evaluation container; never restart a
        # service or conceal permission/stop failures behind another workflow.
        stopped = subprocess.run(['docker', 'stop', '--time', '10', root.name + '-judge'],
                                 stdout=subprocess.DEVNULL, timeout=30)
        if stopped.returncode:
            raise RuntimeError('Evaluation timeout cleanup failed; operator review required')
        raise RuntimeError('Evaluation deadline exceeded')
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
