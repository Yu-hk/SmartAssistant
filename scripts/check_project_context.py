"""Generate/check declared facts using only the standard library; no network or credentials."""
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
START = '<!-- BEGIN GENERATED FACTS -->'
END = '<!-- END GENERATED FACTS -->'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def render(root):
    pom = ET.parse(root / 'pom.xml').getroot()
    def value(path):
        result = pom.findtext(path, namespaces=NS)
        if not result:
            raise ValueError('Missing POM fact: ' + path)
        return result.strip()
    versions = [('Java', value('m:properties/m:java.version')),
                ('Spring Boot', value('m:parent/m:version'))]
    for label, prop in [('Spring AI', 'spring-ai'), ('Spring Cloud', 'spring-cloud'),
                        ('Spring Cloud Alibaba', 'spring-cloud-alibaba'),
                        ('Nacos client', 'nacos-client'), ('langgraph4j', 'langgraph4j')]:
        versions.append((label, value('m:properties/m:' + prop + '.version')))
    package = json.loads((root / 'frontend/package.json').read_text(encoding='utf-8'))
    for name in ['react', 'react-router-dom', 'tdesign-react', 'vite', 'tailwindcss']:
        versions.append((name + ' (声明范围)',
                         (package['dependencies'] | package['devDependencies'])[name]))
    if package['scripts']['dev'] != 'vite':
        raise ValueError('Default frontend dev must use Vite/Gateway, not the legacy Node server')
    lines = ['| 技术 | 仓库声明 |', '| --- | --- |']
    lines += [f'| {name} | `{version}` |' for name, version in versions]
    modules = [node.text.strip() for node in pom.findall('m:modules/m:module', NS)]
    lines += ['', f'Maven 模块共 {len(modules)} 个；以下端口来自各模块 application.yml 默认声明。', '',
              '| 模块 | 默认端口 / 类型 |', '| --- | --- |']
    for module in modules:
        config = root / module / 'src/main/resources/application.yml'
        if module not in {'smart-assistant-common', 'smart-assistant-routing-contract', 'smart-assistant-tool-runtime'}:
            match = re.search(r'^server:\s*\n\s+port:\s*(\d+)\s*(?:#.*)?$',
                              config.read_text(encoding='utf-8'), re.M)
            if not match:
                raise ValueError('Cannot resolve default server port: ' + module)
            port = match[1]
        else:
            port = '共享库（无独立端口）'
        lines.append(f'| `{module}` | {port} |')
    lines += ['', '基础设施镜像（`docker-compose-infra.yml`，不等于线上实测版本）：']
    infra = (root / 'docker-compose-infra.yml').read_text(encoding='utf-8')
    for service in ['redis', 'rabbitmq', 'nacos', 'postgres']:
        block = re.search(r'^  ' + service + r':\n(.*?)(?=^  \w[\w-]*:|\Z)', infra, re.M | re.S)
        image = re.search(r'^    image:\s*(\S+)', block[1], re.M) if block else None
        if not image:
            raise ValueError('Cannot resolve infrastructure image: ' + service)
        lines.append(f'- {service}: `{image[1]}`')
    return '\n'.join(lines)


def expected_context(root):
    content = (root / 'ai-project-context.md').read_text(encoding='utf-8')
    if content.count(START) != 1 or content.count(END) != 1:
        raise ValueError('Expected exactly one generated facts block')
    before, tail = content.split(START)
    _, after = tail.split(END)
    return before + START + '\n' + render(root) + '\n' + END + after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    expected = expected_context(ROOT)
    target = ROOT / 'ai-project-context.md'
    if args.write:
        target.write_text(expected, encoding='utf-8', newline='\n')
    elif target.read_text(encoding='utf-8') != expected:
        print('Project context drift. Run: python scripts/check_project_context.py --write', file=sys.stderr)
        return 1
    print('Project context matches repository declarations')
    return 0


if __name__ == '__main__':
    sys.exit(main())
