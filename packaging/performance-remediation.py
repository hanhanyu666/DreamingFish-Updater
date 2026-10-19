"""Compare two local JARs with deterministic synthetic data; never uses an existing home."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import statistics
import subprocess
import threading
import time
import urllib.request
import uuid
import zipfile
import psutil

parser = argparse.ArgumentParser()
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--current', type=Path, required=True)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--scratch', type=Path, required=True)
parser.add_argument('--rounds', type=int, default=3)
parser.add_argument('--resume', type=Path, help='Reuse only complete, identically configured prior rounds')
parser.add_argument('--extended', action='store_true', help='Also measure unchanged and one-file publication')
parser.add_argument('--scenario',choices=['1088','10000-and-jars'])
parser.add_argument('--variant',choices=['baseline','current'])
parser.add_argument('--single-round',type=int,choices=[1,2,3])
parser.add_argument('--request-timeout',type=int,default=180)
args = parser.parse_args()
output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
scratch = args.scratch.resolve()
if scratch.exists() or args.rounds < 3:
    raise SystemExit('Scratch must be new and at least three rounds are required')
scratch.mkdir(parents=True)
(scratch / '.owned-performance-fixture').write_text(str(output), encoding='utf8')
flags = ['-Xms64m', '-Xmx512m', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
rows = json.loads(args.resume.read_text(encoding='utf8'))['rows'] if args.resume else []
primary={'idle_empty_1s','list_files','repeated_state_30','scan_initial','publish_initial','public_presentation_30','idle_after_work_1s','import_16MiB'}
if not args.extended:
    for row in rows:row['phases']=[phase for phase in row['phases'] if phase['name'] in primary]

def bytes_under(root):
    return sum(p.stat().st_size for p in root.rglob('*') if p.is_file()) if root.exists() else 0

def fixture(source, scenario):
    (source / 'config').mkdir(parents=True); (source / 'resourcepacks').mkdir()
    count = 1024 if scenario == '1088' else 10000
    for n in range(count):
        parent = source / 'config' if scenario == '1088' else source / 'config' / f'group-{n//5:04}'
        parent.mkdir(exist_ok=True)
        (parent / f'{n:05}.txt').write_bytes((f'configuration={n}\n'.encode() + b'x' * 128)[:128])
    block = bytes(range(256)) * 4096
    for n in range(64 if scenario == '1088' else 0):
        with (source / 'resourcepacks' / f'{n:03}.bin').open('wb') as stream:
            stream.write(n.to_bytes(4, 'big') + block[4:])
            for _ in range(3): stream.write(block)
    if scenario != '1088':
        (source / 'mods').mkdir()
        for n in range(32):
            with zipfile.ZipFile(source / 'mods' / f'fixture-{n:02}.jar', 'w') as jar:
                for name, data in [('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': f'benchmark_{n}', 'version': '1.0.0', 'name': f'Fixture {n}'})),
                                   ('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'), ('assets/fixture/data.bin', block[:65536])]:
                    info=zipfile.ZipInfo(name, (2026, 10, 18, 0, 0, 0));jar.writestr(info, data)
    files = sorted(p for p in source.rglob('*') if p.is_file())
    digest = hashlib.sha256(''.join(f'{p.relative_to(source).as_posix()}\0{hashlib.file_digest(p.open("rb"), "sha256").hexdigest()}\n' for p in files).encode()).hexdigest()
    return {'files': len(files), 'bytes': bytes_under(source), 'manifestSha256': digest, 'formatJars': 0 if scenario == '1088' else 32}

for scenario in ['1088', '10000-and-jars']:
    if args.scenario and scenario!=args.scenario:continue
    for round_number in range(1, args.rounds + 1):
        if args.single_round and round_number!=args.single_round:continue
        # Alternate ordering to avoid always giving the repaired version the warmer cache.
        variants = [('baseline', args.baseline), ('current', args.current)]
        if round_number % 2 == 0: variants.reverse()
        for variant, artifact in variants:
            if args.variant and variant!=args.variant:continue
            if any(row['scenario']==scenario and row['round']==round_number and row['variant']==variant for row in rows):continue
            home = scratch / f'{scenario}-{round_number}-{variant}'; home.mkdir()
            source = home / 'source'; data = home / 'data'
            dataset = fixture(source, scenario)
            with socket.socket() as port_socket:
                port_socket.bind(('127.0.0.1', 0)); public_port = port_socket.getsockname()[1]
            (home / 'management-settings.json').write_text(json.dumps({'schemaVersion': 3, 'dataDirectory': str(data), 'defaultProjectId': '', 'httpHost': '127.0.0.1', 'httpPort': public_port, 'webPort': 18080, 'webHost': '127.0.0.1'}), encoding='utf8')
            label = f'{scenario}-{round_number}-{variant}'
            stderr = (output / (label + '.stderr.log')).open('wb')
            command = [str(args.java.resolve()), *flags, '-Ddfs.home=' + str(home), '-jar', str(artifact.resolve()), 'desktop']
            child = subprocess.Popen(command, cwd=home, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, text=True, encoding='utf8', creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            process = psutil.Process(child.pid); token = ''; phases = []
            try:
                ready = json.loads(child.stdout.readline()); base = ready['url'].rstrip('/')
                def api(path, body=None, method=None):
                    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), method=method,
                        headers={'Content-Type': 'application/json', 'Origin': base, 'X-DFS-Token': token})
                    with urllib.request.urlopen(request, timeout=args.request_timeout) as response: return json.load(response)
                token = api('/api/session')['token']
                def measure(name, work):
                    stop = threading.Event(); peak = [0, 0]
                    def sample():
                        while not stop.is_set():
                            try:
                                memory = process.memory_info();peak[0] = max(peak[0], memory.rss);peak[1] = max(peak[1], getattr(memory, 'private', 0))
                            except psutil.NoSuchProcess: break
                            stop.wait(.01)
                    thread = threading.Thread(target=sample, daemon=True)
                    before_cpu = process.cpu_times(); before_io = process.io_counters(); start = time.perf_counter(); thread.start()
                    try: result = work()
                    finally:
                        elapsed = time.perf_counter() - start; after_cpu = process.cpu_times();after_io = process.io_counters();stop.set();thread.join()
                    row = {'name': name, 'seconds': elapsed, 'cpuSeconds': after_cpu.user + after_cpu.system - before_cpu.user - before_cpu.system,
                        'peakWorkingSetMiB': peak[0] / 1024**2, 'peakPrivateBytesMiB': peak[1] / 1024**2,
                        'readMiB': (after_io.read_bytes - before_io.read_bytes) / 1024**2, 'writeMiB': (after_io.write_bytes - before_io.write_bytes) / 1024**2}
                    phases.append(row);print(json.dumps({'run': label, **row}), flush=True);return result
                measure('idle_empty_1s', lambda: time.sleep(1))
                api('/api/public-service/start', {})
                api('/api/projects', {'id': 'benchmark', 'displayName': 'Synthetic benchmark', 'sourceDirectory': str(source), 'publicBaseUrl': f'http://127.0.0.1:{public_port}'})
                measure('list_files', lambda: api('/api/projects/benchmark/files'))
                measure('repeated_state_30', lambda: [api('/api/state') for _ in range(30)])
                preview = measure('scan_initial', lambda: api('/api/projects/benchmark/scan', {}))
                def publish(version, check):
                    return api('/api/projects/benchmark/publish', {'displayVersion': version, 'minimumPlayerVersion': '0.2.0', 'changelog': 'Synthetic benchmark', 'acknowledgePlayerUpgrade': True, 'previewId': check['previewId'], 'previewDigest': check['previewDigest']})
                if scenario=='1088':measure('publish_initial', lambda: publish('1.0.0', preview))
                def repeated_presentation():
                    expected=None
                    for _ in range(30):
                        with urllib.request.urlopen(f'http://127.0.0.1:{public_port}/v1/projects/benchmark/presentation',timeout=15) as response:
                            body=response.read()
                        if expected is None:expected=body
                        elif body!=expected:raise RuntimeError('Unchanged presentation returned different payloads')
                    return hashlib.sha256(expected).hexdigest()
                measure('public_presentation_30',repeated_presentation)
                initial_bytes = bytes_under(data)
                if args.extended and scenario=='1088':
                    preview = measure('scan_unchanged', lambda: api('/api/projects/benchmark/scan', {}))
                    measure('publish_unchanged', lambda: publish('1.0.1', preview))
                    changed = next((source / 'config').rglob('*.txt'));changed.write_bytes(b'changed=' + b'y' * 120)
                    preview = measure('scan_small_change', lambda: api('/api/projects/benchmark/scan', {}))
                    measure('publish_small_change', lambda: publish('1.0.2', preview))
                measure('idle_after_work_1s', lambda: time.sleep(1))
                # Same import contract on both versions: observe durable receipts vs retained large payloads.
                import_file = home / 'import-payload.bin'
                with import_file.open('wb') as stream:
                    for _ in range(16): stream.write(bytes(range(256)) * 4096)
                before_import = bytes_under(data)
                def import_source():
                    plan = api('/api/projects/benchmark/files/stage-server', {'sourcePath': str(import_file), 'targetPath': 'resourcepacks/imported.bin'})
                    return api('/api/projects/benchmark/files/commit', {'id': plan['id'], 'action': 'ADD', 'stamp': plan['stamp']})
                measure('import_16MiB', import_source)
                rows.append({'scenario': scenario, 'round': round_number, 'variant': variant, 'publicationIncluded':scenario=='1088','artifactSha256': hashlib.file_digest(artifact.open('rb'), 'sha256').hexdigest(),
                    'dataset': dataset, 'phases': phases, 'storage': {'afterFirstPublishBytes': initial_bytes, 'beforeImportBytes': before_import, 'afterImportBytes': bytes_under(data),
                    'sourceImportBytes': bytes_under(data / 'source-imports'), 'sourceRecoveryBytes': bytes_under(data / 'source-transactions'), 'objectBytes': bytes_under(data / 'objects')}, 'threadsAtEnd': process.num_threads()})
            finally:
                if child.poll() is None:
                    try: child.communicate('{"command":"shutdown"}\n', timeout=20)
                    except subprocess.TimeoutExpired: child.kill();child.wait(timeout=10)
                stderr.close()
                (output / 'raw.json').write_text(json.dumps({'java': str(args.java), 'javaFlags': flags, 'samplingMillis': 10, 'logicalCPUs': psutil.cpu_count(), 'extended':args.extended,'resumedCompleteRounds':str(args.resume) if args.resume else None,'rows': rows}, indent=2), encoding='utf8')
            # This exact newly-created run is the only deletion target; retain logs and fingerprints separately.
            if home.parent != scratch or not (scratch / '.owned-performance-fixture').is_file() or home.is_symlink():
                raise SystemExit('Refusing to clean an unowned performance fixture')
            shutil.rmtree(home)

summary = []
for scenario in ['1088', '10000-and-jars']:
    for variant in ['baseline', 'current']:
        runs = [row for row in rows if row['scenario'] == scenario and row['variant'] == variant]
        if not runs:continue
        for name in [phase['name'] for phase in runs[0]['phases']]:
            values = [next(phase for phase in run['phases'] if phase['name'] == name) for run in runs]
            summary.append({'scenario': scenario, 'variant': variant, 'phase': name, **{key: statistics.median(value[key] for value in values) for key in values[0] if key != 'name'}})
(output / 'medians.json').write_text(json.dumps(summary, indent=2), encoding='utf8')
print('Performance evidence: ' + str(output), flush=True)
