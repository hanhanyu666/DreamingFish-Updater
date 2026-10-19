"""Read-only OSV checks for public production Maven coordinates and Cargo locks.
No project input, credentials, paths or source is sent to the advisory service.
"""
import argparse
import json
from pathlib import Path
import tomllib
import urllib.request
import zipfile

repo = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--artifact-root', type=Path, default=repo,
                    help='Read freshly built production JARs from this source/build root')
args = parser.parse_args()
output = args.output.resolve()
if not output.is_relative_to((repo / 'target').resolve()):
    raise SystemExit('Use an isolated output under this repository target directory')
output.mkdir(parents=True, exist_ok=True)
packages = {}
for app in ('admin-desktop', 'player-ui'):
    lock = tomllib.loads((repo / app / 'src-tauri/Cargo.lock').read_text(encoding='utf-8'))
    for package in lock['package']:
        if package.get('source', '').startswith('registry+'):
            key = ('crates.io', package['name'], package['version'])
            packages.setdefault(key, []).append(app)
for module, glob in [('management-cli', 'dfs-admin.jar'), ('player-app', 'player-app-*.jar'), ('bootstrap-agent', 'bootstrap-agent-*.jar')]:
    for jar in (args.artifact_root.resolve() / module / 'target').glob(glob):
        if jar.name.startswith('original-'):
            continue
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('META-INF/maven/') and name.endswith('/pom.properties'):
                    values = dict(line.split('=', 1) for line in archive.read(name).decode('utf-8').splitlines() if '=' in line and not line.startswith('#'))
                    if values.get('groupId', '').startswith('cn.dreamingfish'):
                        continue
                    if {'groupId', 'artifactId', 'version'} <= values.keys():
                        key = ('Maven', values['groupId'] + ':' + values['artifactId'], values['version'])
                        packages.setdefault(key, []).append(module)
keys = sorted(packages)
queries = [{'package': {'ecosystem': ecosystem, 'name': name}, 'version': version} for ecosystem, name, version in keys]
(output / 'queries.json').write_text(json.dumps(queries, indent=2), encoding='utf-8')
results = []
for offset in range(0, len(queries), 100):
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps({'queries': queries[offset:offset+100]}).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        results.extend(json.load(response)['results'])
rows = [{'ecosystem': key[0], 'name': key[1], 'version': key[2], 'components': sorted(set(packages[key])), 'advisories': result.get('vulns', [])} for key, result in zip(keys, results)]
(output / 'results.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
affected = [row for row in rows if row['advisories']]
print(json.dumps({'checked': len(rows), 'affectedPackages': len(affected), 'findings': affected}, indent=2))
