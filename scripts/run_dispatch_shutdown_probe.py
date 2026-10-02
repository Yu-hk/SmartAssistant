"""Disposable local Docker proof for the dispatch component, not full Boot shutdown.

No published ports, inherited secrets, shared volumes or external network. Owns only
containers bearing its random scope label. Persist evidence before exact-ID cleanup.
Requires compiled Consumer main/test classes and Maven dependency:build-classpath.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import zipfile

JAVA_IMAGE = 'bellsoft/liberica-openjdk-debian:21-cds'
IMAGES = (JAVA_IMAGE, 'redis:7.2.4', 'rabbitmq:4.1-management-alpine')
MAIN = 'com.example.smartassistant.consumer.service.dispatch.DispatchShutdownFixture'
QUEUE = 'smart.chat.dispatch.v1.quorum'
DLQ = 'smart.chat.dispatch.v1.dead'
LABEL = 'smartassistant.shutdown-probe'


def command(*args, timeout=60):
    result = subprocess.run(['docker', *args], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Docker command {args[0]} failed: {result.stderr[-2000:]}')
    return (result.stdout + result.stderr if args[0] == 'logs' else result.stdout).strip()


def request_key(request):
    return 'chat:dispatch:v1:{dispatch}:request:' + hashlib.sha256(request.encode()).hexdigest()


def require_local_docker(endpoint):
    if not isinstance(endpoint, str) or not endpoint.startswith(('npipe://', 'unix://')):
        raise ValueError('Only a local Docker socket is allowed; remote TCP/SSH engines are refused')


def assert_snapshot(snapshot, status, calls):
    if snapshot['status'] != status or snapshot['calls'] != calls:
        raise AssertionError(f'Wrong durable state: expected {status}/{calls}, got {snapshot}')
    if status == 'COMPLETED' and snapshot['result'].get('result') != 'synthetic verified result':
        raise AssertionError('Completed result missing or changed')
    if status in ('RUNNING', 'QUEUED') and snapshot['result']:
        raise AssertionError('Unconfirmed work fabricated a result')


def verify_stop(state, logs, generation, deliberate_kill=False):
    if state['Running'] or state['OOMKilled']:
        raise AssertionError('Container did not stop cleanly or was OOM-killed')
    if deliberate_kill:
        if state['ExitCode'] != 137:
            raise AssertionError('Deliberate KILL did not produce the expected exit')
    elif state['ExitCode'] != 143 or logs.count('PROBE_TERM_BEGIN ' + generation) != 1 or \
            logs.count('PROBE_TERM_END ' + generation) != 1:
        raise AssertionError('TERM hook incomplete, repeated, or Docker escalated to KILL')


def verify_matrix(cases):
    expected = {(case, repeat) for case in ('empty', 'queued', 'short', 'before-ack', 'running')
                for repeat in range(1, 4)}
    if len(cases) != 15 or {(case['case'], case['repeat']) for case in cases} != expected or \
            any(case['status'] != 'passed' for case in cases):
        raise AssertionError('Incomplete, duplicate or failed matrix must not pass')


def bundle(repo, classpath_file, output):
    target = output / 'bundle'
    target.mkdir()
    inputs = {}
    for module, directory, name in [('smart-assistant-consumer', 'classes', 'consumer.jar'),
                                    ('smart-assistant-consumer', 'test-classes', 'fixture.jar')]:
        root = repo / module / 'target' / directory
        if not root.is_dir():
            raise ValueError('Missing compiled classes: ' + str(root))
        with zipfile.ZipFile(target / name, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(root.rglob('*.class')):
                archive.write(path, path.relative_to(root).as_posix())
        inputs[name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
    paths = classpath_file.read_text(encoding='utf-8').strip().split(os.pathsep)
    for index, raw in enumerate(paths):
        path = Path(raw)
        if not path.is_file() or path.suffix != '.jar':
            raise ValueError('Classpath must contain existing dependency jars only')
        name = f'dep-{index:03d}.jar'
        content = path.read_bytes()
        (target / name).write_bytes(content)
        inputs[name] = hashlib.sha256(content).hexdigest()
    return target, inputs


class Probe:
    def __init__(self, output, mount, entrypoint_form='exec'):
        self.scope = 'sa-stop-' + uuid.uuid4().hex[:16]
        self.output, self.mount = output, mount.resolve()
        self.ids = []
        self.network = None
        self.redis = None
        self.rabbit = None
        self.entrypoint_form = entrypoint_form
        self.processes = {}

    def create(self, name, image, *args):
        identifier = command('create', '--name', self.scope + '-' + name,
                             '--label', LABEL + '=' + self.scope, '--network', self.scope,
                             *args, image)
        self.ids.append(identifier)
        command('start', identifier)
        return identifier

    def setup(self):
        context = command('context', 'show')
        endpoint = os.environ.get('DOCKER_HOST') or json.loads(command('context', 'inspect', context))[0]['Endpoints']['docker']['Host']
        require_local_docker(endpoint)
        for image in IMAGES:
            command('image', 'inspect', image)  # no implicit pulls
        self.network = command('network', 'create', '--internal', '--label', LABEL + '=' + self.scope, self.scope)
        self.redis = self.create('redis', IMAGES[1], '--network-alias', 'redis', '--memory', '128m',
                                 '--tmpfs', '/data')
        self.rabbit = self.create('rabbit', IMAGES[2], '--network-alias', 'rabbitmq', '--memory', '768m',
                                  '--tmpfs', '/var/lib/rabbitmq:uid=100,gid=101,mode=0700', '-e', 'RABBITMQ_DEFAULT_USER=probe',
                                  '-e', 'RABBITMQ_DEFAULT_PASS=isolated-test-only',
                                  '-e', 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2',
                                  '-e', 'RABBITMQ_CTL_ERL_ARGS=+S 1:1')
        self.until(lambda: self.redis_command('PING') == 'PONG', 30)
        # Running the health command as root can create a root-only cookie before
        # the broker starts; use the verified UID/GID of this pinned Alpine image.
        self.until(lambda: command('exec', '--user', '100:101', self.rabbit, 'rabbitmq-diagnostics', '-q', 'ping', timeout=20)
                   == 'Ping succeeded', 90)

    @staticmethod
    def until(check, timeout=30):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                value = check()
                if value:
                    return value
            except (RuntimeError, AssertionError) as error:
                last = error
            time.sleep(.1)
        raise AssertionError('Probe condition timed out: ' + str(last))

    def redis_command(self, *args):
        return command('exec', self.redis, 'redis-cli', '--raw', *args, timeout=15)

    def set(self, name, value='1'):
        self.redis_command('SET', 'shutdown-probe:' + name, value, 'EX', '600')

    def worker(self, generation, start=True):
        identifier = command('create', '--name', self.scope + '-' + generation, '--label', LABEL + '=' + self.scope,
                             '--network', self.scope, '--memory', '512m', '--cap-drop', 'ALL',
                             '--security-opt', 'no-new-privileges', '--read-only', '--tmpfs', '/tmp',
                             '--mount', 'type=bind,source=' + str(self.mount) + ',target=/probe,readonly',
                             '--entrypoint', 'sh', JAVA_IMAGE, '-c',
                             ('exec ' if self.entrypoint_form == 'exec' else '') +
                             'java -Xmx256m -cp "/probe/*" ' + MAIN + ' worker ' + generation)
        self.ids.append(identifier)
        command('start', identifier)
        self.until(lambda: self.redis_command('GET', 'shutdown-probe:ready:' + generation) == '1', 45)
        pid_one = command('exec', identifier, 'cat', '/proc/1/comm')
        if pid_one != ('java' if self.entrypoint_form == 'exec' else 'sh'):
            raise AssertionError('Unexpected PID 1; signal-entry comparison is invalid')
        self.processes[generation] = {'container_id': identifier, 'pid_one': pid_one}
        if start:
            self.set('start:' + generation)
            self.until(lambda: 'PROBE_CONSUMING ' + generation in command('logs', identifier), 30)
        return identifier

    def seed(self, request):
        # Explicit per-container args; no host environment or production config imports.
        identifier = command('create', '--label', LABEL + '=' + self.scope, '--network', self.scope,
                             '--memory', '512m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                             '--read-only', '--tmpfs', '/tmp', '--mount',
                             'type=bind,source=' + str(self.mount) + ',target=/probe,readonly',
                             '--entrypoint', 'java', JAVA_IMAGE, '-Xmx256m', '-cp', '/probe/*', MAIN, 'seed', request)
        self.ids.append(identifier)
        command('start', '-a', identifier, timeout=45)
        state = json.loads(command('inspect', identifier))[0]['State']
        if state['ExitCode'] != 0:
            raise AssertionError('Seed JVM failed: ' + command('logs', identifier))

    def snapshot(self, request):
        raw = self.redis_command('HGET', request_key(request), 'result')
        return {'status': self.redis_command('HGET', request_key(request), 'status'),
                'calls': int(self.redis_command('GET', 'shutdown-probe:calls:' + request) or '0'),
                'result': json.loads(raw) if raw else {}}

    def queues(self):
        raw = command('exec', '--user', '100:101', self.rabbit, 'rabbitmqctl', '-q', 'list_queues',
                      'name', 'messages_ready', 'messages_unacknowledged', 'consumers', '--formatter=json', timeout=20)
        return json.loads(raw)

    def stop(self, identifier, generation, kill=False):
        started = time.monotonic()
        command('kill', '--signal=KILL', identifier) if kill else command('stop', '--time', '12', identifier, timeout=25)
        elapsed = round(time.monotonic() - started, 3)
        data = json.loads(command('inspect', identifier))[0]
        logs = command('logs', identifier)
        (self.output / (generation + '.log')).write_text(logs + '\n', encoding='utf-8')
        (self.output / (generation + '.inspect.json')).write_text(json.dumps(data, indent=2), encoding='utf-8')
        verify_stop(data['State'], logs, generation, kill)
        return {'elapsed_seconds': elapsed, 'state': data['State'],
                'term_begin': logs.count('PROBE_TERM_BEGIN ' + generation),
                'term_end': logs.count('PROBE_TERM_END ' + generation),
                'intentional_kill': kill}

    def drained(self):
        rows = {row['name']: row for row in self.queues()}
        queue = rows.get(QUEUE, {})
        return queue.get('messages_ready') == 0 and queue.get('messages_unacknowledged') == 0

    def case(self, case, repeat):
        generation = f'{case}-{repeat}'
        request = generation + '-' + uuid.uuid4().hex[:8]
        identifier = self.worker(generation, start=case != 'queued')
        report = {'case': case, 'repeat': repeat, 'request_id': request, 'started_at': utc_now()}
        if case == 'empty':
            report['stop'] = self.stop(identifier, generation)
            report['queues'] = self.queues()
            report['status'] = 'passed'
            return report
        if case in ('running', 'short'):
            self.set('hold:' + request, 'route')
        elif case == 'before-ack':
            self.set('hold:' + request, 'ack')
        self.seed(request)
        target = 'QUEUED' if case == 'queued' else 'COMPLETED' if case == 'before-ack' else 'RUNNING'
        self.until(lambda: self.snapshot(request)['status'] == target)
        if case == 'before-ack':
            self.until(lambda: self.redis_command('GET', 'shutdown-probe:ack-barrier:' + request) == '1')
        assert_snapshot(self.snapshot(request), target, 0 if case == 'queued' else 1)
        report['before'] = self.snapshot(request)
        report['queues_before'] = self.queues()
        if case == 'short':
            with ThreadPoolExecutor(max_workers=1) as executor:
                stopping = executor.submit(self.stop, identifier, generation)
                self.until(lambda: 'PROBE_TERM_BEGIN ' + generation in command('logs', identifier))
                self.set('release:' + request)
                report['stop'] = stopping.result(timeout=25)
        else:
            report['stop'] = self.stop(identifier, generation, kill=case == 'running')
        report['after_stop'] = self.snapshot(request)
        assert_snapshot(report['after_stop'], 'COMPLETED' if case in ('short', 'before-ack') else target,
                        0 if case == 'queued' else 1)
        self.set('release:' + request)
        restarted = self.worker(generation + '-restart')
        self.until(self.drained, 30)
        report['after_restart'] = self.snapshot(request)
        assert_snapshot(report['after_restart'], 'RUNNING' if case == 'running' else 'COMPLETED', 1)
        report['queues_after_restart'] = self.queues()
        if case == 'running':
            dead = next(row for row in report['queues_after_restart'] if row['name'] == DLQ)
            if dead['messages_ready'] < repeat:
                raise AssertionError('Ambiguous running delivery was not dead-lettered')
        report['restart_stop'] = self.stop(restarted, generation + '-restart')
        report['status'] = 'passed'
        return report

    def cleanup(self):
        results = []
        for identifier in reversed(self.ids):
            data = json.loads(command('inspect', identifier))[0]
            if data['Config']['Labels'].get(LABEL) != self.scope:
                raise AssertionError('Refusing cleanup outside owned scope')
            # Retain failed/bootstrap process evidence too, before removing our tmpfs.
            (self.output / (identifier[:12] + '.inspect.json')).write_text(json.dumps(data, indent=2), encoding='utf-8')
            (self.output / (identifier[:12] + '.log')).write_text(command('logs', identifier) + '\n', encoding='utf-8')
            command('rm', '-f', identifier)
            results.append(identifier)
        if self.network:
            data = json.loads(command('network', 'inspect', self.network))[0]
            if data['Labels'].get(LABEL) != self.scope or data['Containers']:
                raise AssertionError('Refusing network cleanup outside empty owned scope')
            command('network', 'rm', self.network)
        return {'removed_own_containers': results, 'network_removed': bool(self.network)}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--entrypoint-form', choices=('exec', 'shell'), default='exec',
                        help='exec is the isolated control; shell reproduces the current launch shape, not a production jar')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'scope': 'dispatch-component-only', 'started_at': utc_now(), 'cases': [],
              'normal_repeats': 3, 'production_operations': 0,
              'entrypoint_form': args.entrypoint_form,
              'not_covered': ['actual production jar/entrypoint', 'HTTP/SSE', 'PG/checkpoints', 'Router leases', 'profile/log tails']}
    probe = None
    try:
        mount, report['artifacts_sha256'] = bundle(args.repo.resolve(), args.classpath_file, args.output)
        sources = [Path(__file__).resolve(), args.repo / 'smart-assistant-consumer/src/test/java/com/example/smartassistant/consumer/service/dispatch/DispatchShutdownFixture.java']
        sources += list((args.repo / 'smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/service/dispatch').glob('*.java'))
        sources += list((args.repo / 'smart-assistant-consumer/src/main/java/com/example/smartassistant/consumer/config').glob('ChatDispatch*.java'))
        report['source_sha256'] = {path.relative_to(args.repo.resolve()).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                                   for path in sources}
        report['git_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.repo, text=True).strip()
        probe = Probe(args.output, mount, args.entrypoint_form)
        report['owned_scope'] = probe.scope
        probe.setup()
        report['images'] = {image: json.loads(command('image', 'inspect', image))[0]['Id'] for image in IMAGES}
        for case in ('empty', 'queued', 'short', 'before-ack', 'running'):
            for repeat in range(1, 4):
                report['cases'].append(probe.case(case, repeat))
                print(f'{case} {repeat}/3 passed', flush=True)
        verify_matrix(report['cases'])
        report['status'] = 'passed'
    except Exception as error:
        report['status'], report['error'] = 'failed', str(error)
        raise
    finally:
        try:
            if probe:
                report['processes'] = probe.processes
                report['cleanup'] = probe.cleanup()
        except Exception as error:
            report['status'], report['cleanup_error'] = 'failed', str(error)
            raise
        finally:
            report['finished_at'] = utc_now()
            (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
