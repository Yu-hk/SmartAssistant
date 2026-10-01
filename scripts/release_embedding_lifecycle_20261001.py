"""One-off Embedding class-overlay release; no whole-system shutdown claim.

Run only from the fixed release directory. Inspection snapshots contain credentials
and are written with mode 0600, never printed. Canary calls only the eight synthetic
internal embedding HTTP checks. Normal stop failures and exit 137 are not ignored.
The existing gateway drain covers its historical queues/ACTIVE_RUNNING check only.
"""
import argparse
import copy
import hashlib
import json
import os
import pathlib
import re
import shutil
import stat
import sys
import time
import zipfile

import deploy_assessment_20260925 as helper
from verify_embedding_service_live import address, request, verify

ROOT = pathlib.Path('/opt/smart-assistant/releases/assessment-contracts-20261001')
SERVICE = 'smart-embedding-service'
NETWORK = 'smart-network'
CANARY = 'smart-embedding-contract-canary-20261001'
PREFLIGHT = 'smart-embedding-contract-preflight-20261001'
PREVIOUS = 'smart-embedding-service-before-assessment-contracts-20261001'
FAILED = 'smart-embedding-service-failed-assessment-contracts-20261001'
ENTRY = 'BOOT-INF/classes/com/example/smartassistant/embedding/EmbeddingApplication.class'
BASELINE_SHA = '27b51d0a143fdf7b49d20e0105a4f3f54c89238f2998f8554dc240abcb9631c4'
IMAGE = '45262a7ae83795d5ae65e1480ea0485283f04d162672cc3f1a80440e0ae79adb'
BASELINE_JAR = '/opt/smart-assistant/smart-assistant-embedding-service/target/smart-assistant-embedding-service-1.0.0-SNAPSHOT.jar'
MODELS = '/opt/smart-assistant/models'
COMMAND = ['java', '-Dfile.encoding=UTF-8', '-Xms512m', '-Xmx1536m', '-jar',
           '/app/app.jar', '--server.port=8091']
LABEL = 'smartassistant.embedding-contract-release'
TAG = 'assessment-contracts-20261001'
DISABLE_DISCOVERY = '--spring.cloud.nacos.discovery.enabled=false'
run = helper.run
drain = helper.drain
public_health = helper.public_health
require = helper.require


def inspect(name):
    return json.loads(run('docker', 'inspect', name))[0]


def exists(name):
    # Inspection errors are not silently mistaken for "container absent".
    return name in run('docker', 'ps', '-a', '--format', '{{.Names}}').splitlines()


def digest(path):
    return helper.sha256(path)


def image_id(value):
    return value[7:] if value.startswith('sha256:') else value


def stop_signal(value):
    # Podman inspects this field as an integer; Docker may use a string.
    # Only that representation difference is accepted, not an alternate signal.
    require((type(value) is int and value == 15) or (type(value) is str and value == '15'),
            'Original stop signal must be integer 15 or string 15')
    return '15'


def runtime_name(info):
    declared = info['HostConfig'].get('Runtime')
    if declared == 'oci':
        # Podman 4.9 uses a generic inspect marker, not an executable OCI name.
        # This release was reviewed against runc only; no inferred/default fallback.
        require(type(info.get('OCIRuntime')) is str and info['OCIRuntime'] == 'runc',
                'Podman oci marker requires reviewed actual runc metadata')
        return info['OCIRuntime']
    return declared


def reviewed_podman(info):
    if info['HostConfig'].get('Runtime') != 'oci':
        return False
    runtime_name(info)
    annotations = info['Config'].get('Annotations')
    require(type(annotations) is dict and annotations.get('io.container.manager') == 'libpod',
            'Reviewed Podman annotation metadata required')
    require(all(type(key) is str and key and '=' not in key and type(value) is str
                for key, value in annotations.items()), 'Invalid Podman annotation metadata')
    return True


def environment_map(values):
    require(type(values) is list, 'Environment list required')
    result = {}
    for entry in values:
        require(type(entry) is str and '=' in entry, 'Invalid environment entry')
        key, value = entry.split('=', 1)
        require(key and key not in result, 'Duplicate or empty environment name')
        result[key] = value
    return result


def podman_log_config(info):
    result = copy.deepcopy(info['HostConfig'].get('LogConfig'))
    require(type(result) is dict, 'Podman log configuration required')
    # Only this exact runtime-generated default is container-identity dependent.
    # Explicit paths and every other logging option still compare without change.
    default = '/var/lib/containers/storage/overlay-containers/' + info['Id'] + '/userdata/ctr.log'
    if result.get('Type') == 'k8s-file' and result.get('Path') == default:
        result['Path'] = '<own-container-default-k8s-log>'
    return result


def resource_gate():
    require(shutil.disk_usage(str(ROOT)).free >= 1024 ** 3, 'At least 1 GiB free release disk required')
    memory = pathlib.Path('/proc/meminfo').read_text(encoding='ascii')
    available = re.search(r'^MemAvailable:\s+(\d+)\s+kB$', memory, re.MULTILINE)
    require(available is not None and int(available.group(1)) * 1024 >= 3 * 1024 ** 3,
            'At least 3 GiB MemAvailable required before cloning')


def private_json(name, data, replace=False):
    path = ROOT / name
    require(path.parent == ROOT and path.name == name, 'Receipt path outside release root')
    require(not path.is_symlink(), 'Receipt is a symlink')
    if not replace:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as output:
            json.dump(data, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(path, 0o600)
        return
    require(path.is_file() and stat.S_IMODE(path.stat().st_mode) & 0o077 == 0,
            'Existing receipt must be restricted')
    temporary = ROOT / (name + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def read_private(name):
    path = ROOT / name
    require(path.is_file() and not path.is_symlink(), 'Missing restricted receipt')
    require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, 'Receipt permissions must be 0600')
    return json.loads(path.read_text(encoding='utf-8'))


def jar_mount(info):
    mounts = [mount for mount in info['Mounts'] if mount['Destination'] == '/app/app.jar']
    require(len(mounts) == 1 and mounts[0]['Type'] == 'bind' and not mounts[0]['RW'],
            'Exactly one read-only JAR bind mount required')
    return mounts[0]['Source']


def verify_artifact(manifest):
    required = {'baseline_sha256': BASELINE_SHA, 'classes_replaced': 1,
                'embedding_entries_replaced': [ENTRY], 'unrelated_entries_preserved': True}
    for key, expected in required.items():
        require(manifest.get(key) == expected, 'Candidate manifest scope mismatch: ' + key)
    require(type(manifest['classes_replaced']) is int, 'Invalid replacement count')
    require(manifest['unrelated_entries_preserved'] is True, 'Unrelated entry preservation required')
    for key in ('sha256', 'before_class_sha256', 'after_class_sha256'):
        require(isinstance(manifest.get(key), str) and re.fullmatch('[0-9a-f]{64}', manifest[key]),
                'Invalid candidate digest: ' + key)
    candidate = ROOT / 'embedding-candidate.jar'
    require(digest(BASELINE_JAR) == BASELINE_SHA, 'Baseline JAR drift')
    require(digest(candidate) == manifest['sha256'], 'Candidate JAR digest mismatch')
    with zipfile.ZipFile(BASELINE_JAR) as old, zipfile.ZipFile(candidate) as new:
        old_names, new_names = old.namelist(), new.namelist()
        require(len(old_names) == len(set(old_names)) and len(new_names) == len(set(new_names)),
                'Duplicate ZIP entries forbidden')
        require(set(old_names) == set(new_names) and ENTRY in old_names, 'ZIP entry set drift')
        for name in old_names:
            before, after = old.read(name), new.read(name)
            if name != ENTRY:
                require(before == after, 'Unrelated ZIP entry changed')
            else:
                require(before != after, 'Embedding entry was not changed')
                require(hashlib.sha256(before).hexdigest() == manifest['before_class_sha256'],
                        'Before class digest mismatch')
                require(hashlib.sha256(after).hexdigest() == manifest['after_class_sha256'],
                        'After class digest mismatch')
    return manifest['sha256']


def identity(info):
    result = {key: info.get(key) for key in ('Id', 'Image', 'Config', 'HostConfig', 'Mounts')}
    if info['HostConfig'].get('Runtime') == 'oci':
        result['OCIRuntime'] = runtime_name(info)
    return result


def binary_gate(manifest):
    proof = read_private('binary-verified-v2.json')
    require(proof.get('status') == 'passed' and proof.get('baseline_sha256') == BASELINE_SHA
            and proof.get('sha256') == manifest['sha256'] and proof.get('changed_entries') == [ENTRY]
            and proof.get('method_bytecode_identical') is True
            and proof.get('annotations_bound_checked') is True
            and type(proof.get('model_calls')) is int and proof['model_calls'] == 0,
            'Matching annotation-bound independent v2 binary proof required')


def validate_live(info):
    require(info['Name'].lstrip('/') == SERVICE and info['State']['Running'], 'Original service not running')
    require(image_id(info['Image']) == IMAGE, 'Original image drift')
    require(set(info['NetworkSettings']['Networks']) == {NETWORK}, 'Original network drift')
    config = info['Config']
    require(config['Entrypoint'] in (['/__cacert_entrypoint.sh'], '/__cacert_entrypoint.sh'),
            'Original entrypoint drift')
    require(config['Cmd'] == COMMAND, 'Original Java command drift')
    stop_signal(config.get('StopSignal'))
    require(config.get('StopTimeout') == 10, 'Original stop configuration drift')
    runtime_name(info)
    if reviewed_podman(info):
        environment_map(config.get('Env'))
    require(jar_mount(info) == BASELINE_JAR and digest(BASELINE_JAR) == BASELINE_SHA,
            'Original JAR mount drift')
    models = [mount for mount in info['Mounts'] if mount['Destination'] == '/app/models']
    require(len(models) == 1 and models[0]['Type'] == 'bind' and not models[0]['RW']
            and models[0]['Source'] == MODELS, 'Original models mount drift')
    require(all(mount['Type'] == 'bind' for mount in info['Mounts']), 'Unsupported non-bind mount')
    require(run('docker', 'exec', info['Id'], 'sha256sum', '/app/app.jar').split()[0] == BASELINE_SHA,
            'Original container JAR digest drift')


def prepare():
    require(not (ROOT / 'deployment.json').exists(), 'Deployment already attempted; manual review required')
    resource_gate()
    manifest = json.loads((ROOT / 'candidate.json').read_text(encoding='utf-8'))
    verify_artifact(manifest)
    binary_gate(manifest)
    old = inspect(SERVICE)
    validate_live(old)
    snapshot = ROOT / 'before-container.json'
    if snapshot.exists():
        require(identity(read_private(snapshot.name)) == identity(old), 'Original snapshot identity drift')
    else:
        private_json(snapshot.name, old)
    return old, manifest


def expected_source(source, canary=False):
    result = copy.deepcopy(source)
    result['Config']['Labels'] = dict(result['Config'].get('Labels') or {})
    result['Config']['Labels'][LABEL] = TAG
    if canary:
        result['Config']['Cmd'] = list(result['Config']['Cmd']) + [DISABLE_DISCOVERY]
    for mount in result['Mounts']:
        if mount['Destination'] == '/app/app.jar':
            mount['Source'] = str(ROOT / 'embedding-candidate.jar')
    return result


def clone(source, name, canary=False):
    require(name in (SERVICE, PREFLIGHT, CANARY), 'Clone name outside embedding release')
    require(canary == (name == CANARY), 'Canary mode/name mismatch')
    source = expected_source(source, canary)
    config, host = source['Config'], source['HostConfig']
    podman = reviewed_podman(source)
    if podman:
        environment_map(config.get('Env'))
    require(not any(host.get(key) for key in ('PortBindings', 'Privileged', 'Devices', 'VolumesFrom',
            'Links', 'AutoRemove', 'PublishAllPorts', 'ContainerIDFile')), 'Unsupported host settings')
    require(not config.get('StdinOnce') and not config.get('Healthcheck'), 'Unsupported container config')
    restart = host['RestartPolicy']
    policy = restart['Name'] or 'no'
    if restart.get('MaximumRetryCount'):
        require(policy == 'on-failure', 'Invalid restart retry policy')
        policy += ':' + str(restart['MaximumRetryCount'])
    args = ['docker', 'create', '--name', name, '--network', NETWORK, '--network-alias', name,
            '--restart', policy, '--entrypoint', '/__cacert_entrypoint.sh',
            '--workdir', config.get('WorkingDir') or '/', '--stop-signal', stop_signal(config['StopSignal']),
            '--stop-timeout', str(config['StopTimeout'])]
    for flag, key in (('--hostname', 'Hostname'), ('--domainname', 'Domainname'), ('--user', 'User')):
        if config.get(key):
            args += [flag, str(config[key])]
    for flag, key in (('--interactive', 'OpenStdin'), ('--tty', 'Tty')):
        if config.get(key):
            args.append(flag)
    for stream, key in (('stdin', 'AttachStdin'), ('stdout', 'AttachStdout'), ('stderr', 'AttachStderr')):
        if config.get(key):
            args += ['--attach', stream]
    for port in config.get('ExposedPorts') or {}:
        args += ['--expose', port]
    actual_runtime = runtime_name(source)
    if actual_runtime:
        args += ['--runtime', actual_runtime]
    for flag, key in (('--memory', 'Memory'), ('--memory-swap', 'MemorySwap'),
            ('--memory-reservation', 'MemoryReservation'), ('--cpu-shares', 'CpuShares'),
            ('--cpu-period', 'CpuPeriod'), ('--cpu-quota', 'CpuQuota'), ('--cpuset-cpus', 'CpusetCpus'),
            ('--cpuset-mems', 'CpusetMems'), ('--shm-size', 'ShmSize'), ('--pids-limit', 'PidsLimit'),
            ('--oom-score-adj', 'OomScoreAdj'), ('--blkio-weight', 'BlkioWeight'),
            ('--cgroup-parent', 'CgroupParent'), ('--ipc', 'IpcMode'), ('--pid', 'PidMode'),
            ('--uts', 'UTSMode'), ('--userns', 'UsernsMode'), ('--cgroupns', 'CgroupnsMode')):
        if host.get(key):
            args += [flag, str(host[key])]
    if host.get('NanoCpus'):
        args += ['--cpus', str(host['NanoCpus'] / 1_000_000_000)]
    for flag, key in (('--read-only', 'ReadonlyRootfs'), ('--oom-kill-disable', 'OomKillDisable'),
                      ('--init', 'Init')):
        if host.get(key):
            args.append(flag)
    for flag, key in (('--dns', 'Dns'), ('--dns-search', 'DnsSearch'), ('--dns-option', 'DnsOptions'),
            ('--add-host', 'ExtraHosts'), ('--cap-add', 'CapAdd'), ('--cap-drop', 'CapDrop'),
            ('--security-opt', 'SecurityOpt'), ('--group-add', 'GroupAdd')):
        for value in host.get(key) or []:
            args += [flag, value]
    for value in host.get('Ulimits') or []:
        args += ['--ulimit', '{}={}:{}'.format(value['Name'], value['Soft'], value['Hard'])]
    for key, value in (host.get('Sysctls') or {}).items():
        args += ['--sysctl', key + '=' + str(value)]
    for key, value in (host.get('Tmpfs') or {}).items():
        args += ['--tmpfs', key + (':' + value if value else '')]
    for mount in source['Mounts']:
        require(not any(',' in str(mount[key]) for key in ('Source', 'Destination')), 'Unsupported mount syntax')
        value = 'type=bind,source=' + mount['Source'] + ',target=' + mount['Destination']
        if not mount['RW']:
            value += ',readonly'
        if mount.get('Propagation'):
            value += ',bind-propagation=' + mount['Propagation']
        args += ['--mount', value]
    logging = host.get('LogConfig') or {}
    if logging.get('Type'):
        args += ['--log-driver', logging['Type']]
    for key, value in (logging.get('Config') or {}).items():
        args += ['--log-opt', key + '=' + value]
    for key, value in config['Labels'].items():
        args += ['--label', key + '=' + value]
    if podman:
        for key, value in config['Annotations'].items():
            args += ['--annotation', key + '=' + value]
    for value in config.get('Env') or []:
        args += ['--env', value]
    return run(*args, source['Image'], *config['Cmd'])


def equivalent(old, new, canary=False):
    expected = expected_source(old, canary)
    require(expected['Image'] == new['Image'], 'Candidate image drift')
    # No unsupported Config/HostConfig field may silently vanish during CLI cloning.
    # Docker records the image reference used at creation separately from the
    # immutable Image ID. The clone deliberately uses that same immutable ID.
    old_config = {key: value for key, value in expected['Config'].items() if key != 'Image'}
    new_config = {key: value for key, value in new['Config'].items() if key != 'Image'}
    old_config['StopSignal'] = stop_signal(old_config.get('StopSignal'))
    new_config['StopSignal'] = stop_signal(new_config.get('StopSignal'))
    podman = reviewed_podman(expected)
    if podman:
        require(reviewed_podman(new), 'Candidate Podman metadata drift')
        old_config['Env'] = environment_map(old_config.get('Env'))
        new_config['Env'] = environment_map(new_config.get('Env'))
        for config in (old_config, new_config):
            command = config.get('CreateCommand')
            require(type(command) is list and command and all(type(value) is str for value in command),
                    'Generated Podman creation command metadata required')
            config.pop('CreateCommand')  # Historical original remains in the unmodified private snapshot.
    require(old_config == new_config, 'Candidate Config drift (including stop fields)')
    for key in set(expected['HostConfig']) | set(new['HostConfig']):
        if key in ('Binds', 'Mounts'):
            continue  # Compared semantically below; the JAR bind source intentionally changes.
        if key == 'LogConfig' and podman:
            require(podman_log_config(expected) == podman_log_config(new), 'Candidate Podman log config drift')
            continue
        require(expected['HostConfig'].get(key) == new['HostConfig'].get(key), 'Candidate HostConfig drift: ' + key)
    if expected['HostConfig'].get('Runtime') == 'oci':
        require(runtime_name(expected) == runtime_name(new), 'Candidate actual OCI runtime drift')
    elif 'OCIRuntime' in expected:
        require(expected['OCIRuntime'] == new.get('OCIRuntime'), 'Candidate actual OCI runtime drift')
    def mounts(info):
        return sorted((mount['Type'], mount['Source'], mount['Destination'], mount['RW'],
                       mount.get('Propagation', '')) for mount in info['Mounts'])
    require(mounts(expected) == mounts(new), 'Candidate mount drift')
    require(set(new['NetworkSettings']['Networks']) == {NETWORK}, 'Candidate network drift')
    if canary:
        require(SERVICE not in (new['NetworkSettings']['Networks'][NETWORK].get('Aliases') or []),
                'Canary must not share the production service alias')


def owned(info, expected_hash):
    require(info['Config'].get('Labels', {}).get(LABEL) == TAG, 'Candidate ownership drift')
    require(image_id(info['Image']) == IMAGE, 'Candidate image ownership drift')
    require(jar_mount(info) == str(ROOT / 'embedding-candidate.jar')
            and digest(jar_mount(info)) == expected_hash, 'Candidate artifact ownership drift')


def preflight(old):
    require(not exists(PREFLIGHT), 'Preflight name already exists')
    cid = clone(old, PREFLIGHT)
    try:
        candidate = inspect(cid)
        require(not candidate['State']['Running'], 'Preflight must not start')
        equivalent(old, candidate)
    finally:
        candidate = inspect(cid)
        require(candidate['Config'].get('Labels', {}).get(LABEL) == TAG
                and not candidate['State']['Running'], 'Preflight cleanup ownership drift')
        run('docker', 'rm', cid)  # Never rm -f.


def wait(name):
    for _ in range(90):
        info = inspect(name)
        require(info['State']['Running'], 'Embedding process exited before readiness')
        try:
            if request(address(name), '/actuator/health').get('status') == 'UP':
                return
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise TimeoutError('Embedding readiness timeout')


def live_hash(name, expected):
    require(run('docker', 'exec', name, 'sha256sum', '/app/app.jar').split()[0] == expected,
            'Running embedding artifact digest mismatch')


def verify8(name):
    result = verify(name)
    require(result.get('status') == 'passed' and result.get('checks') == 8
            and result.get('business_writes') == 0 and result.get('restarts') == 0,
            'Embedding eight-check receipt incomplete')
    return result


def stop_checked(name, receipt):
    started = time.monotonic()
    error = None
    try:
        run('docker', 'stop', '--time', '60', name, timeout=90)
    except Exception as failure:
        error = failure
    state = inspect(name)['State']
    result = {'container': name, 'stop_time_seconds': 60, 'elapsed_seconds': time.monotonic() - started,
              'cli_ok': error is None, 'running': state['Running'], 'status': state.get('Status'),
              'exit_code': state.get('ExitCode'), 'oom_killed': state.get('OOMKilled', False),
              'finished_at': state.get('FinishedAt')}
    private_json(receipt, result)
    if error is not None:
        raise RuntimeError('Normal stop command failed; restricted receipt retained') from None
    require(not state['Running'] and state.get('Status') == 'exited'
            and type(state.get('ExitCode')) is int and state['ExitCode'] in (0, 143)
            and not state.get('OOMKilled'), 'Normal stop did not complete safely')
    return result


def canary(old, manifest):
    require(not exists(CANARY) and not (ROOT / 'canary-verified.json').exists(), 'Canary already attempted')
    cid = clone(old, CANARY, canary=True)
    result = {'status': 'failed', 'sha256': manifest['sha256'], 'baseline_sha256': BASELINE_SHA,
              'original_id': old['Id'], 'canary_id': cid, 'business_writes': 0,
              'scope': 'synthetic-internal-embedding-canary-not-whole-system-shutdown'}
    failure = None
    base = None
    try:
        equivalent(old, inspect(cid), canary=True)
        run('docker', 'start', cid, timeout=90)
        wait(CANARY)
        base = address(CANARY)
        live_hash(cid, manifest['sha256'])
        result['verification'] = verify8(CANARY)
    except Exception as error:
        failure = type(error).__name__
        result['verification_error'] = failure
    try:
        owned(inspect(cid), manifest['sha256'])
        result['stop'] = stop_checked(cid, 'canary-stop.json')
        if base is not None:
            try:
                request(base, '/actuator/health')
            except OSError:
                result['http_unreachable_after_stop'] = True
            else:
                raise RuntimeError('Stopped canary HTTP endpoint still responds')
        # Optional observability: keep only the predefined cleanup message count,
        # never the raw log (which can contain deployment-specific information).
        try:
            result['cleanup_message_count'] = run('docker', 'logs', cid).count('[EmbeddingService] BGE 模型已释放')
        except Exception as log_error:
            result['cleanup_log_error_type'] = type(log_error).__name__
    except Exception as error:
        failure = failure or type(error).__name__
        result['stop_error'] = type(error).__name__
    try:
        require(identity(inspect(SERVICE)) == identity(old), 'Original changed during canary')
    except Exception as error:
        failure = failure or type(error).__name__
        result['original_error'] = type(error).__name__
    if failure is None:
        result['status'] = 'passed'
    private_json('canary-verified.json', result)
    require(failure is None, 'Canary failed; retain container and inspect restricted receipts')
    print('CANARY_VERIFIED_AND_NORMAL_STOP_CONFIRMED', flush=True)


def canary_gate(old, manifest):
    receipt = read_private('canary-verified.json')
    require(receipt.get('status') == 'passed' and receipt.get('sha256') == manifest['sha256']
            and receipt.get('baseline_sha256') == BASELINE_SHA and receipt.get('original_id') == old['Id'],
            'No matching verified canary')
    require(receipt.get('verification', {}).get('status') == 'passed'
            and receipt['verification'].get('checks') == 8
            and receipt['verification'].get('business_writes') == 0
            and receipt['verification'].get('restarts') == 0
            and receipt.get('stop', {}).get('cli_ok') is True
            and receipt['stop'].get('exit_code') in (0, 143)
            and receipt['stop'].get('running') is False and receipt['stop'].get('status') == 'exited'
            and receipt['stop'].get('oom_killed') is False and receipt['stop'].get('stop_time_seconds') == 60
            and receipt.get('http_unreachable_after_stop') is True, 'Canary stop/probe evidence incomplete')
    retained = inspect(CANARY)
    require(retained['Id'] == receipt['canary_id'] and not retained['State']['Running']
            and retained['State'].get('Status') == 'exited' and retained['State'].get('ExitCode') in (0, 143)
            and not retained['State'].get('OOMKilled'),
            'Retained canary state drift')
    owned(retained, manifest['sha256'])
    equivalent(old, retained, canary=True)


def restore_gateway(gateway):
    current = inspect('smart-gateway')
    require(current['Id'] == gateway['Id'], 'Gateway identity drift')
    if not current['State']['Running']:
        run('docker', 'start', gateway['Id'], timeout=90)
    public_health()


def rollback(old, expected):
    result = {}
    if exists(SERVICE):
        current = inspect(SERVICE)
        if current['Id'] != old['Id']:
            owned(current, expected)
            if current['State']['Running']:
                try:
                    result['candidate_stop'] = stop_checked(current['Id'], 'rollback-candidate-stop.json')
                except Exception as error:
                    # A failed stop (including 137) remains a recorded failure,
                    # but an actually stopped candidate need not block restoration.
                    result['candidate_stop_error_type'] = type(error).__name__
            require(not inspect(current['Id'])['State']['Running'], 'Rollback candidate still running')
            require(not exists(FAILED), 'Failed candidate name already occupied')
            run('docker', 'rename', current['Id'], FAILED)
            if NETWORK in inspect(current['Id'])['NetworkSettings']['Networks']:
                run('docker', 'network', 'disconnect', NETWORK, current['Id'])
    previous = inspect(old['Id'])
    require(previous['Name'].lstrip('/') in (SERVICE, PREVIOUS), 'Previous container identity drift')
    require(identity(previous) == identity(old), 'Previous runtime/artifact configuration drift')
    if previous['Name'].lstrip('/') == PREVIOUS:
        run('docker', 'rename', old['Id'], SERVICE)
    if NETWORK not in inspect(old['Id'])['NetworkSettings']['Networks']:
        ip = old['NetworkSettings']['Networks'][NETWORK]['IPAddress']
        run('docker', 'network', 'connect', '--ip', ip, '--alias', SERVICE, NETWORK, old['Id'])
    require(digest(BASELINE_JAR) == BASELINE_SHA, 'Rollback baseline artifact changed')
    if not inspect(old['Id'])['State']['Running']:
        run('docker', 'start', old['Id'], timeout=90)
    wait(SERVICE)
    live_hash(SERVICE, BASELINE_SHA)
    return result


def deploy(old, manifest):
    canary_gate(old, manifest)
    require(not exists(PREVIOUS) and not exists(FAILED), 'Reserved recovery container name occupied')
    gateway = inspect('smart-gateway')
    require(gateway['State']['Running'], 'Gateway not running')
    report = {'status': 'in_progress', 'original_id': old['Id'], 'sha256': manifest['sha256'],
              'baseline_sha256': BASELINE_SHA, 'scope': 'embedding-only-class-overlay',
              'drain_scope': 'existing-chat-profile-queues-and-ACTIVE_RUNNING-not-all-inflight',
              'preserved_stop_signal': old['Config']['StopSignal'],
              'preserved_stop_timeout': old['Config']['StopTimeout']}
    private_json('deployment.json', report)  # Attempt lock: failures also require manual review before rerun.
    gateway_touched = False
    failure = None
    try:
        gateway_touched = True  # Set before the command; it may fail after stopping the process.
        run('docker', 'stop', '--time', '30', gateway['Id'], timeout=90)
        require(not inspect(gateway['Id'])['State']['Running'], 'Gateway did not stop')
        drain()
        require(identity(inspect(SERVICE)) == identity(old), 'Original changed after preflight')
        report['previous_stop'] = stop_checked(old['Id'], 'original-stop.json')
        run('docker', 'rename', old['Id'], PREVIOUS)
        run('docker', 'network', 'disconnect', NETWORK, old['Id'])
        cid = clone(old, SERVICE)
        report['candidate_id'] = cid
        equivalent(old, inspect(cid))
        run('docker', 'start', cid, timeout=90)
        wait(SERVICE)
        live_hash(SERVICE, manifest['sha256'])
        report['verification'] = verify8(SERVICE)
        restore_gateway(gateway)
        report['status'] = 'deployed'
    except Exception as error:
        failure = type(error).__name__
        report['failure_type'] = failure
        try:
            # Public health may fail after gateway restart. Re-pause before reverting.
            if inspect(gateway['Id'])['State']['Running']:
                run('docker', 'stop', '--time', '30', gateway['Id'], timeout=90)
                drain()
            report['rollback'] = rollback(old, manifest['sha256'])
            report['status'] = 'rolled_back'
        except Exception as rollback_error:
            report['status'] = 'rollback_failed'
            report['rollback_error_type'] = type(rollback_error).__name__
    finally:
        if gateway_touched:
            try:
                restore_gateway(gateway)
                report['gateway_restored'] = True
            except Exception as gateway_error:
                report['gateway_restored'] = False
                report['gateway_error_type'] = type(gateway_error).__name__
                failure = failure or type(gateway_error).__name__
        private_json('deployment.json', report, replace=True)
    require(failure is None and report['status'] == 'deployed',
            'Deployment failed; inspect restricted rollback receipt')
    print('EMBEDDING_DEPLOYED_AND_VERIFIED', flush=True)


def main(mode):
    require(mode in ('preflight', 'canary', 'deploy'), 'Expected preflight, canary or deploy')
    require(pathlib.Path(__file__).resolve().parent == ROOT and ROOT.resolve() == ROOT,
            'Fixed release directory required')
    old, manifest = prepare()
    preflight(old)
    print('EMBEDDING_PREFLIGHT_OK', flush=True)
    if mode == 'canary':
        canary(old, manifest)
    elif mode == 'deploy':
        deploy(old, manifest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('preflight', 'canary', 'deploy'))
    args = parser.parse_args()
    try:
        main(args.mode)
    except Exception as error:
        # No exception text, Docker stderr, environment or credentials enter stdout/stderr.
        print('EMBEDDING_RELEASE_FAILED ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
