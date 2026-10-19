"""Bounded local measurements of the real management JAR, using synthetic data only."""
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request
import uuid
import psutil

repo = Path(__file__).resolve().parents[3]
root = repo / 'target' / 'review-2026-10-14' / ('resources-' + str(uuid.uuid4()))
root.mkdir(parents=True)
source = root / 'source'
(source / 'config').mkdir(parents=True)
(source / 'resourcepacks').mkdir()
for n in range(1024):
    (source / 'config' / f'{n:04}.txt').write_bytes((f'configuration={n}\n'.encode() + b'x' * 128)[:128])
block = bytes(range(256)) * 4096
for n in range(64):
    with (source / 'resourcepacks' / f'{n:03}.bin').open('wb') as out:
        out.write(n.to_bytes(4, 'big') + block[4:])
        for _ in range(3):
            out.write(block)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    public_port = sock.getsockname()[1]
(root / 'management-settings.json').write_text(json.dumps({
    'schemaVersion': 3, 'dataDirectory': str(root / 'data'), 'defaultProjectId': '',
    'httpHost': '127.0.0.1', 'httpPort': public_port, 'webPort': 18080, 'webHost': '127.0.0.1'
}), encoding='utf-8')
java = Path(os.environ.get('REVIEW_JDK', r'D:\Program Files\Java\jdk-21')) / 'bin/java.exe'
errors = (root / 'stderr.log').open('w', encoding='utf-8')
child = subprocess.Popen([str(java), '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8', '-Ddfs.home=' + str(root), '-jar',
    str(repo / 'management-cli/target/dfs-admin.jar'), 'desktop'], cwd=root,
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True, encoding='utf-8',
    creationflags=subprocess.CREATE_NO_WINDOW)
observations = {'jdk': str(java), 'files': 1088, 'managedBytes': 256 * 1024**2 + 1024 * 128,
    'logicalCPUs': psutil.cpu_count(), 'hostMemoryGiB': round(psutil.virtual_memory().total / 1024**3, 1),
    'phases': []}
try:
    ready = json.loads(child.stdout.readline())
    base = ready['url'].rstrip('/')
    process = psutil.Process(child.pid)
    session = ''
    def api(path, body=None, method=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(base + path, data=data, method=method,
            headers={'Content-Type': 'application/json', 'Origin': base, 'X-DFS-Token': session})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    session = api('/api/session')['token']
    api('/api/public-service/start', {})
    def measure(name, work):
        peak = [process.memory_info().rss]
        stop = threading.Event()
        def sample():
            while not stop.wait(0.02):
                try:
                    peak[0] = max(peak[0], process.memory_info().rss)
                except psutil.NoSuchProcess:
                    break
        thread = threading.Thread(target=sample, daemon=True)
        before = process.cpu_times()
        io_before = process.io_counters()
        start = time.perf_counter()
        thread.start()
        try:
            result = work()
        finally:
            elapsed = time.perf_counter() - start
            after = process.cpu_times()
            io_after = process.io_counters()
            stop.set()
            thread.join()
        cpu = (after.user + after.system) - (before.user + before.system)
        row = {'name': name, 'seconds': round(elapsed, 3), 'cpuSeconds': round(cpu, 3),
            'peakRssMiB': round(peak[0] / 1024**2, 1), 'endRssMiB': round(process.memory_info().rss / 1024**2, 1),
            'readMiB': round((io_after.read_bytes - io_before.read_bytes) / 1024**2, 1),
            'writeMiB': round((io_after.write_bytes - io_before.write_bytes) / 1024**2, 1)}
        observations['phases'].append(row)
        print(json.dumps(row), flush=True)
        return result
    measure('idle_empty_5s', lambda: time.sleep(5))
    api('/api/projects', {'id': 'review', 'displayName': 'Review synthetic data',
        'sourceDirectory': str(source), 'publicBaseUrl': f'http://127.0.0.1:{public_port}'})
    measure('list_1088_files', lambda: api('/api/projects/review/files'))
    preview = measure('scan_256MiB', lambda: api('/api/projects/review/scan', {}))
    def publish(version, preview):
        return api('/api/projects/review/publish', {'displayVersion': version, 'minimumPlayerVersion': '0.2.0',
            'changelog': 'Synthetic review benchmark', 'acknowledgePlayerUpgrade': True,
            'previewId': preview['previewId'], 'previewDigest': preview['previewDigest']})
    measure('first_publish', lambda: publish('1.0.0', preview))
    preview = measure('scan_unchanged', lambda: api('/api/projects/review/scan', {}))
    measure('publish_unchanged', lambda: publish('1.0.1', preview))
    measure('idle_after_work_5s', lambda: time.sleep(5))
    observations['dataBytes'] = sum(p.stat().st_size for p in (root / 'data').rglob('*') if p.is_file())
finally:
    try:
        if child.poll() is None:
            child.stdin.write('{"command":"shutdown"}\n')
            child.stdin.flush()
            child.communicate(timeout=15)
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=5)
        errors.close()
    (root / 'results.json').write_text(json.dumps(observations, indent=2), encoding='utf-8')
    print('Resource artifacts: ' + str(root), flush=True)
