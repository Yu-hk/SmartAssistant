"""Measure the reactor's audited lightweight suite; never certify external integrations.

Maven offline mode restricts dependency resolution, not JVM network access. The suite
inventory and clean reports are retained so exclusions cannot masquerade as passes.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SAFE_INTEGRATIONS = {
    'com.example.smartassistant.common.memory.AgentMemoryServiceIntegrationTest',
    'com.example.smartassistant.consumer.controller.ChatIntegrationTest',
    'com.example.smartassistant.gateway.filter.GlobalJwtAuthFilterIntegrationTest',
}
SPECIAL_EXCLUSIONS = {
    'RagRerankTest': 'real_onnx_model',
    'BgeModelComparisonDiagnosticTest': 'real_onnx_model',
    'ConcurrencyLoadTest': 'separate_synthetic_load',
    'TestRecordingKnowledgeBase': 'helper_source',
    'TestSlotSchemas': 'helper_source',
    'ProfileControlJournalTest': 'separate_linux_posix',
    'ProfileLegacyFilesTest': 'separate_linux_posix',
}
COUNTERS = ('INSTRUCTION', 'BRANCH', 'LINE', 'METHOD', 'CLASS', 'COMPLEXITY')
# Deliberately allow system/runtime variables, not arbitrary model/storage/test env.
ENV_ALLOWLIST = {
    'PATH', 'JAVA_HOME', 'MAVEN_HOME', 'M2_HOME', 'SYSTEMROOT', 'WINDIR',
    'COMSPEC', 'PATHEXT', 'SYSTEMDRIVE', 'USERPROFILE', 'HOMEDRIVE',
    'HOMEPATH', 'HOME', 'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL',
    'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS',
}
DEFENSIVE_PROPERTIES = [
    '-Dspring.config.import=',
    '-Dspring.cloud.nacos.discovery.enabled=false',
    '-Dspring.cloud.nacos.config.enabled=false',
    '-Dspring.cloud.service-registry.auto-registration.enabled=false',
    '-Dmanagement.logging.export.enabled=false',
    '-Dmanagement.tracing.enabled=false',
    '-Dpg.integration=false', '-Dprofile.pg.integration=false',
    '-Didentity.pg.integration=false',
]


def clean_environment(source):
    return {key: value for key, value in source.items() if key.upper() in ENV_ALLOWLIST}


def execute(command, repo, environment, log, timeout):
    """Own one subprocess tree; timeouts must not leave Maven's JVM running."""
    options = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
    process = subprocess.Popen(command, cwd=repo, env=environment, stdout=log,
                               stderr=subprocess.STDOUT, **options)
    try:
        return process.wait(timeout=timeout)
    except BaseException:
        if process.poll() is None:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                               capture_output=True, timeout=30, check=True)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=30)
        raise


def inventory(repo):
    modules = [node.text for node in ET.parse(repo / 'pom.xml').findall('m:modules/m:module', NS)]
    if not modules or len(modules) != len(set(modules)) or any(not re.fullmatch(r'[\w-]+', name) for name in modules):
        raise ValueError('missing or duplicate reactor modules')
    result = []
    for module in modules:
        directory = repo / module / 'src/test/java'
        suites = []
        for path in sorted(directory.rglob('*.java')):
            if not re.fullmatch(r'(?:Test.*|.*Tests?|.*IT)', path.stem):
                continue
            package = re.search(r'^package\s+([\w.]+)\s*;', path.read_text(encoding='utf-8'), re.M)
            if not package:
                raise ValueError('test source without package: ' + str(path))
            name = package.group(1) + '.' + path.stem
            reason = SPECIAL_EXCLUSIONS.get(path.stem)
            if path.stem.endswith('IntegrationTest') and name not in SAFE_INTEGRATIONS:
                reason = 'external_storage_or_broker'
            suites.append({'name': name, 'source': path.relative_to(repo).as_posix(),
                           'source_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                           'selected': reason is None, 'exclusion': reason})
        if not suites or not any(suite['selected'] for suite in suites):
            raise ValueError('module without a selected suite: ' + module)
        result.append({'module': module, 'suites': suites})
    return result


def verify_policy(items, policy):
    if policy.get('schema_version') != 1:
        raise ValueError('invalid reviewed suite policy')
    actual = [{'module': item['module'], 'suites': [{key: suite[key] for key in
              ('name', 'source', 'selected', 'exclusion')} for suite in item['suites']]} for item in items]

    def canonical_modules(modules):
        # Native Path ordering folds case on Windows, but not on Linux. Suite
        # order is not part of selection; retain exact names/paths/classification
        # and duplicate counts instead of weakening this gate to a set comparison.
        result = []
        for item in modules:
            suites = item['suites']
            if any(len({suite[key] for suite in suites}) != len(suites)
                   for key in ('name', 'source')):
                raise ValueError('suite inventory changed: duplicate suite name or source')
            result.append(dict(item, suites=sorted(suites, key=lambda suite: (suite['source'], suite['name']))))
        return result

    if canonical_modules(actual) != canonical_modules(policy.get('modules', [])):
        raise ValueError('suite inventory changed; explicitly review/update offline-baseline-policy.json')


def int_count(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d+', value):
        raise ValueError('missing, invalid or negative report counter')
    return int(value)


def read_module(repo, item, report_root=None):
    module = item['module']
    selected = {suite['name'] for suite in item['suites'] if suite['selected']}
    executed = Counter()
    totals = Counter(tests=0, failures=0, errors=0, skipped=0)
    suite_directory = report_root / module / 'surefire' if report_root else repo / module / 'target/surefire-reports'
    reports = sorted(suite_directory.glob('TEST-*.xml'))
    if not reports:
        raise ValueError(module + ': missing Surefire reports')
    for report in reports:
        root = ET.parse(report).getroot()
        if root.tag != 'testsuite':
            raise ValueError(module + ': invalid report root')
        name = root.get('name', '')
        owner = name.split('$', 1)[0]
        if owner not in selected:
            raise ValueError(module + ': unexpected suite: ' + name)
        counts = {key: int_count(root.get(key)) for key in totals}
        cases = list(root.findall('testcase'))
        if len(cases) != counts['tests']:
            raise ValueError(module + ': testcase/count mismatch: ' + name)
        for key, tag in (('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')):
            if counts[key] or any(case.find(tag) is not None for case in cases):
                raise ValueError(module + ': ' + key + ' in ' + name)
        executed[owner] += counts['tests']
        totals.update(counts)
    missing = sorted(name for name in selected if not executed[name])
    if missing:
        raise ValueError(module + ': no executed tests for ' + ', '.join(missing))
    path = report_root / module / 'jacoco.xml' if report_root else repo / module / 'target/site/jacoco/jacoco.xml'
    root = ET.parse(path).getroot()
    if root.tag != 'report':
        raise ValueError(module + ': invalid JaCoCo root')
    coverage = {}
    for kind in COUNTERS:
        nodes = root.findall("counter[@type='" + kind + "']")
        if len(nodes) != 1:
            raise ValueError(module + ': missing/duplicate JaCoCo counter: ' + kind)
        covered, missed = (int_count(nodes[0].get(key)) for key in ('covered', 'missed'))
        denominator = covered + missed
        if kind in ('INSTRUCTION', 'LINE', 'CLASS') and denominator == 0:
            raise ValueError(module + ': empty JaCoCo counter: ' + kind)
        coverage[kind] = {'covered': covered, 'missed': missed,
                          'ratio': covered / denominator if denominator else None}
    return {'module': module, 'selected_source_suites': len(selected),
            'report_files': len(reports), 'testcases': dict(totals), 'coverage': coverage}


def comparable(report):
    result = {key: report[key] for key in ('source_fingerprint', 'inventory', 'policy_sha256',
                                         'defensive_properties', 'runner_sha256', 'java', 'maven')}
    result['modules'] = [{**module, 'coverage': {kind: counter['covered'] + counter['missed']
                         for kind, counter in module['coverage'].items()}} for module in report['modules']]
    return result


def compare_reports(first, second):
    if comparable(first) != comparable(second):
        raise ValueError('repeat differs in source, selection, tests, tools or instrumented denominators')
    ranges = []
    for left, right in zip(first['modules'], second['modules']):
        for kind in COUNTERS:
            a, b = left['coverage'][kind], right['coverage'][kind]
            denominator = a['covered'] + a['missed']
            low, high = sorted((a['covered'], b['covered']))
            ranges.append({'module': left['module'], 'counter': kind,
                           'covered_min': low, 'covered_max': high, 'denominator': denominator,
                           'ratio_min': low / denominator if denominator else None,
                           'ratio_max': high / denominator if denominator else None})
    return {'coverage_repeat_exact': all(row['covered_min'] == row['covered_max'] for row in ranges),
            'coverage_ranges': ranges}


def fingerprint(repo, items):
    digest = hashlib.sha256()
    paths = [repo / 'pom.xml']
    for item in items:
        base = repo / item['module']
        paths.extend([base / 'pom.xml'])
        paths.extend(path for path in (base / 'src').rglob('*') if path.is_file())
    for path in sorted(paths):
        digest.update(path.relative_to(repo).as_posix().encode('utf-8') + b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def snapshot_reports(repo, output, items):
    """Preserve each run before the next clean deletes module targets."""
    for item in items:
        module = item['module']
        directory = output / 'reports' / module
        for source in sorted((repo / module / 'target/surefire-reports').glob('TEST-*.xml')):
            target = directory / 'surefire' / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        source = repo / module / 'target/site/jacoco/jacoco.xml'
        if source.exists():
            directory.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, directory / 'jacoco.xml')


def run(repo, output, maven, previous=None, timeout=2700):
    repo, output = repo.resolve(), output.resolve()
    safe_root = repo / '.codex-output/offline-baseline'
    if safe_root not in output.parents or output.exists():
        raise ValueError('use a NEW directory below .codex-output/offline-baseline/')
    items = inventory(repo)
    policy_path = repo / 'scripts/offline-baseline-policy.json'
    verify_policy(items, json.loads(policy_path.read_text(encoding='utf-8')))
    # Contexts import ./ and ../ .env; reject even though a system property disables it.
    env_paths = [repo / '.env', repo.parent / '.env'] + [repo / item['module'] / '.env' for item in items]
    if any(path.exists() for path in env_paths):
        raise ValueError('real .env present in test configuration search paths; use an isolated checkout')
    if any((repo / '.mvn' / name).exists() for name in ('maven.config', 'jvm.config', 'extensions.xml')):
        raise ValueError('custom Maven startup configuration requires separate review')
    output.mkdir(parents=True)
    includes = output / 'includes.txt'
    selected = sorted(suite['name'].replace('.', '/') + '.java'
                      for item in items for suite in item['suites'] if suite['selected'])
    includes.write_text('\n'.join(selected) + '\n', encoding='utf-8')
    environment = clean_environment(os.environ)
    def info(command):
        result = subprocess.run(command, cwd=repo, env=environment, capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=30)
        if result.returncode:
            raise ValueError('version/commit query failed')
        return result.stdout.strip() or result.stderr.strip()
    report = {'schema_version': 1, 'status': 'running', 'scope': 'audited_lightweight_reactor',
              'started_at': datetime.now(timezone.utc).isoformat(), 'commit': info(['git', 'rev-parse', 'HEAD']),
              'source_fingerprint': fingerprint(repo, items), 'java': info(['java', '-version']),
              'maven': info([maven, '-version']), 'inventory': items,
              'policy_sha256': hashlib.sha256(policy_path.read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'defensive_properties': list(DEFENSIVE_PROPERTIES),
              'excluded_counts': dict(Counter(suite['exclusion'] for item in items for suite in item['suites'] if not suite['selected'])),
              'modules': [], 'external_integrations_executed': False,
              'jvm_network_sandboxed': False, 'production_access_configured': False}
    command = [maven, '-o', '-B', '-fae', 'clean', 'test', '-Dsurefire.includesFile=' + str(includes),
               '-Dsurefire.failIfNoSpecifiedTests=true'] + DEFENSIVE_PROPERTIES
    report['command'] = command
    started = time.monotonic()
    error = None
    try:
        with (output / 'maven.log').open('w', encoding='utf-8') as log:
            exit_code = execute(command, repo, environment, log, timeout)
        report['maven_exit_code'] = exit_code
        if exit_code:
            raise ValueError('Maven failed; inspect retained maven.log')
        report['modules'] = [read_module(repo, item) for item in items]
        if fingerprint(repo, items) != report['source_fingerprint']:
            raise ValueError('source changed during baseline')
        report['status'] = 'passed'
        if previous:
            old = json.loads(previous.read_text(encoding='utf-8'))
            if old.get('status') != 'passed':
                raise ValueError('previous measurement did not pass')
            report.update(compare_reports(old, report))
            report['repeat_of'] = hashlib.sha256(previous.read_bytes()).hexdigest()
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError, KeyboardInterrupt) as failure:
        report['status'], error = 'failed', str(failure)
        report['error'] = error
    report['elapsed_seconds'] = round(time.monotonic() - started, 3)
    snapshot_reports(repo, output, items)
    (output / 'baseline.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if error:
        raise ValueError(error)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--maven', default='mvn.cmd' if os.name == 'nt' else 'mvn')
    parser.add_argument('--compare', type=Path)
    args = parser.parse_args()
    try:
        report = run(args.repo, args.output, args.maven, args.compare)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print('Measured ' + str(len(report['modules'])) + ' modules; tests=' +
          str(sum(module['testcases']['tests'] for module in report['modules'])) +
          '; excluded=' + str(report['excluded_counts']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
