"""Three interleaved hidden-startup rounds of two local desktop artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import time
import psutil

parser=argparse.ArgumentParser()
parser.add_argument('--baseline',type=Path,required=True)
parser.add_argument('--current',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
expected_build=json.loads((args.current.resolve().parent/'core/build-info.json').read_text(encoding='utf8'))['buildId']
rows=[]
for number in range(1,4):
    variants=[('baseline',args.baseline),('current',args.current)]
    if number%2==0:variants.reverse()
    for variant,exe in variants:
        run=output/f'{number}-{variant}';home=run/'home';home.mkdir(parents=True)
        env=dict(os.environ,JAVA_TOOL_OPTIONS='-Xms64m -Xmx512m',DFS_JAVA_HEAP_MB='512')
        log=(run/'process.log').open('wb')
        child=subprocess.Popen([str(exe.resolve()),'--background-check','--desktop-home',str(home)],cwd=exe.resolve().parent,
            env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
        root=psutil.Process(child.pid);owned={(root.pid,root.create_time()):root};counters={};peak=[0,0];start=time.perf_counter();timed_out=False
        while child.poll() is None:
            if time.perf_counter()-start>60:timed_out=True;break
            try:
                for p in root.children(recursive=True):owned[(p.pid,p.create_time())]=p
            except psutil.NoSuchProcess:pass
            rss=private=0
            for identity,p in owned.items():
                try:
                    if p.create_time()!=identity[1]:continue
                    memory=p.memory_info();cpu=p.cpu_times();io=p.io_counters();rss+=memory.rss;private+=getattr(memory,'private',0)
                    counters[identity]={'name':p.name(),'cpuSeconds':cpu.user+cpu.system,'readBytes':io.read_bytes,'writeBytes':io.write_bytes}
                except (psutil.NoSuchProcess,psutil.AccessDenied):pass
            peak=[max(peak[0],rss),max(peak[1],private)];time.sleep(.02)
        elapsed=time.perf_counter()-start
        if timed_out:
            for identity,p in reversed(list(owned.items())):
                try:
                    if p.create_time()==identity[1]:p.kill()
                except (psutil.NoSuchProcess,psutil.AccessDenied):pass
        child.wait(timeout=10);log.close()
        lingering=[];deadline=time.monotonic()+10
        while True:
            lingering=[]
            for identity,p in owned.items():
                try:
                    if p.create_time()==identity[1] and p.is_running():lingering.append(identity[0])
                except psutil.NoSuchProcess:pass
            if not lingering or time.monotonic()>deadline:break
            time.sleep(.1)
        for identity,p in owned.items():
            if identity[0] not in lingering:continue
            try:
                if p.create_time()==identity[1]:p.kill()
            except (psutil.NoSuchProcess,psutil.AccessDenied):pass
        report_file=home/'background-check.json';report=json.loads(report_file.read_text(encoding='utf8')) if report_file.exists() else {}
        rows.append({'round':number,'variant':variant,'passed':not timed_out and not lingering and child.returncode==0 and report.get('success') is True and (variant=='baseline' or report.get('buildId')==expected_build),
            'seconds':elapsed,'peakSummedWorkingSetMiB':peak[0]/1024**2,'peakSummedPrivateBytesMiB':peak[1]/1024**2,
            'cpuSeconds':sum(p['cpuSeconds'] for p in counters.values()),'readMiB':sum(p['readBytes'] for p in counters.values())/1024**2,
            'writeMiB':sum(p['writeBytes'] for p in counters.values())/1024**2,'lingeringOwnedPids':lingering,'report':report,
            'artifactSha256':hashlib.file_digest(exe.open('rb'),'sha256').hexdigest(),'sampledProcessCounters':list(counters.values())})
        (output/'raw.json').write_text(json.dumps({'samplingMillis':20,'workingSetsDoubleCountSharedPages':True,'countersAreSampledNotExactLifetimeTotals':True,'javaFlags':['-Xms64m','-Xmx512m'],'rows':rows},indent=2),encoding='utf8')
        print(json.dumps({k:v for k,v in rows[-1].items() if k not in ('report','sampledProcessCounters')},ensure_ascii=False),flush=True)
summary=[]
for variant in ['baseline','current']:
    samples=[r for r in rows if r['variant']==variant]
    summary.append({'variant':variant,'allPassed':all(r['passed'] for r in samples),**{key:statistics.median(r[key] for r in samples) for key in ['seconds','peakSummedWorkingSetMiB','peakSummedPrivateBytesMiB','cpuSeconds','readMiB','writeMiB']}})
(output/'medians.json').write_text(json.dumps(summary,indent=2),encoding='utf8')
raise SystemExit(0 if all(r['passed'] for r in rows) else 1)
