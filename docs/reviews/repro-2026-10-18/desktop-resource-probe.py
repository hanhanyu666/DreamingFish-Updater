"""Measure the current release executable's hidden, isolated background check.

This is a startup/connection smoke measurement, not a steady-state GUI benchmark.
Only copied build artifacts and synthetic --desktop-home data are used.
"""
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import psutil

repo = Path(__file__).resolve().parents[3]
root = repo / 'target/review-2026-10-18' / ('desktop-' + str(uuid.uuid4()))
program = root / 'program'
core = program / 'core'
(core / 'support').mkdir(parents=True)
exe = program / 'DreamingFishAdminDesktop.exe'
shutil.copy2(repo / 'admin-desktop/src-tauri/target/release/dreamingfish-admin-desktop.exe', exe)
shutil.copy2(repo / 'management-cli/target/dfs-admin.jar', core / 'dfs-admin.jar')
agent = next(p for p in (repo / 'bootstrap-agent/target').glob('bootstrap-agent-*.jar')
             if not p.name.startswith('original-'))
shutil.copy2(agent, core / 'support/bootstrap-agent.jar')
shutil.copytree(repo / 'admin-desktop/src-tauri/resources/core/runtime', core / 'runtime')
home = root / 'isolated-home'
child = subprocess.Popen([str(exe), '--background-check', '--desktop-home', str(home)],
                         cwd=program, creationflags=subprocess.CREATE_NO_WINDOW,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
process = psutil.Process(child.pid)
started = time.perf_counter()
peak_rss = 0
peak_private = 0
peak_processes = 0
peak_groups = {}
seen = {}
timed_out = False
try:
    while child.poll() is None and time.perf_counter() - started < 60:
        groups = {}
        private = 0
        count = 0
        try:
            descendants = [process, *process.children(recursive=True)]
        except psutil.NoSuchProcess:
            break
        for item in descendants:
            try:
                mem = item.memory_info()
                name = item.name()
                groups[name] = groups.get(name, 0) + mem.rss
                private += getattr(mem, 'private', 0)
                seen[item.pid] = item.create_time()
                count += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                pass
        rss = sum(groups.values())
        if rss > peak_rss:
            peak_rss = rss
            peak_groups = {name: round(size / 1024**2, 1) for name, size in groups.items()}
        peak_private = max(peak_private, private)
        peak_processes = max(peak_processes, count)
        time.sleep(0.02)
    if child.poll() is None:
        timed_out = True
finally:
    if child.poll() is None:
        # Stop only processes observed in this newly launched child's tree, with PID reuse checks.
        for pid, created in reversed(list(seen.items())):
            try:
                owned = psutil.Process(pid)
                if owned.create_time() == created:
                    owned.kill()
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                pass
        child.wait(timeout=10)

check = home / 'background-check.json'
result = {
    'kind': 'hidden_startup_and_connection_smoke',
    'seconds': round(time.perf_counter() - started, 3),
    'exitCode': child.returncode,
    'timedOut': timed_out,
    'peakSumWorkingSetMiB': round(peak_rss / 1024**2, 1),
    'peakSumPrivateBytesMiB': round(peak_private / 1024**2, 1),
    'maxProcesses': peak_processes,
    'groupsAtPeakWorkingSetMiB': peak_groups,
    'check': json.loads(check.read_text(encoding='utf-8')) if check.exists() else None,
    'note': 'Working sets sum shared pages more than once; private bytes are committed memory. Neither is a steady-state idle measurement.',
}
(root / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
print('Desktop artifacts: ' + str(root))
if child.returncode or not result['check'] or not result['check'].get('success'):
    raise SystemExit('Hidden check did not succeed; another existing single instance may prevent a new run.')
