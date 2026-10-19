"""Verify locally built artifacts without launching or publishing them."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--expected', required=True)
parser.add_argument('--admin-jar', type=Path)
parser.add_argument('--player-jar', type=Path)
parser.add_argument('--bootstrap-jar', type=Path)
parser.add_argument('--core-directory', type=Path)
parser.add_argument('--write-core-info', action='store_true')
args = parser.parse_args()
if not re.fullmatch('[0-9a-f]{64}', args.expected):
    raise SystemExit('Expected a source build identity')

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def check_jar(path, component):
    with zipfile.ZipFile(path) as jar:
        marker = 'META-INF/dreamingfish/build/' + component + '.json'
        try:
            info = json.loads(jar.read(marker))
        except KeyError:
            raise SystemExit(f'{path} lacks its build identity; rebuild it before packaging')
        if info.get('schemaVersion') != 1 or info.get('buildId') != args.expected or info.get('component') != component:
            raise SystemExit(f'{path} belongs to another source/build')
        forbidden = re.compile(r'(^|/)(connections\.json|management-settings\.json|management-web-auth\.json|[^/]+\.pk8|source-imports|source-transactions|source-archive|source-operations|application-tasks|storage-leases)(/|$)', re.I)
        if any(forbidden.search(name) for name in jar.namelist()):
            raise SystemExit(f'{path} contains private management data')
        if component == 'management-cli':
            for directory in ['cn/dreamingfish/updater/management/cli/web/workbench/', 'cn/dreamingfish/updater/management/cli/web/player-preview/']:
                frontend = json.loads(jar.read(directory + 'build-info.json'))
                if frontend['buildId'] != args.expected:
                    raise SystemExit(f'{path} embeds a stale frontend')
                for relative, expected in frontend['outputs'].items():
                    if hashlib.sha256(jar.read(directory + relative)).hexdigest() != expected:
                        raise SystemExit(f'{path} has inconsistent frontend asset {relative}')
        return info

for path, component in [(args.admin_jar, 'management-cli'), (args.player_jar, 'player-app'), (args.bootstrap_jar, 'bootstrap-agent')]:
    if path:
        check_jar(path, component)
if args.core_directory:
    core = args.core_directory.resolve()
    admin, bootstrap = core / 'dfs-admin.jar', core / 'support/bootstrap-agent.jar'
    check_jar(admin, 'management-cli')
    check_jar(bootstrap, 'bootstrap-agent')
    actual = {'schemaVersion': 1, 'buildId': args.expected, 'adminJarSha256': digest(admin), 'bootstrapJarSha256': digest(bootstrap)}
    info = core / 'build-info.json'
    if args.write_core_info:
        info.write_text(json.dumps(actual, indent=2) + '\n', encoding='utf-8')
    elif json.loads(info.read_text(encoding='utf-8')) != actual:
        raise SystemExit('Desktop bundled core differs from its artifact identity')
print(json.dumps({'success': True, 'buildId': args.expected}))
