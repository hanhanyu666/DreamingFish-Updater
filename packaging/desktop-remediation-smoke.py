"""Hidden tests of a locally packaged desktop, with fresh data and owned PIDs only."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import psutil
import shutil
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--exe',type=Path,required=True)
parser.add_argument('--core',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--only',choices=['normal','both','limit','init-hang','stale-core'])
args=parser.parse_args()
output=args.output.resolve()
output.mkdir(parents=True,exist_ok=False)
if not (args.core/'build-info.json').is_file():
    raise SystemExit('Package lacks its verified core identity')
expected_build=json.loads((args.core/'build-info.json').read_text(encoding='utf8'))['buildId']
rows=[]
for mode in ([args.only] if args.only else ['normal','both','limit','init-hang','stale-core']):
    home=output/mode/'home';home.mkdir(parents=True)
    executable=args.exe.resolve()
    if mode=='stale-core':
        program=home.parent/'program';program.mkdir();shutil.copyfile(executable,program/executable.name)
        shutil.copytree(args.core,program/'core');jar=program/'core/dfs-admin.jar';temporary=jar.with_suffix('.modified')
        with zipfile.ZipFile(jar) as original,zipfile.ZipFile(temporary,'w') as changed:
            for entry in original.infolist():
                data=original.read(entry)
                if entry.filename=='META-INF/dreamingfish/build/management-cli.json':
                    marker=json.loads(data);marker['buildId']='0'*64;data=json.dumps(marker).encode('utf8')
                changed.writestr(entry,data)
        temporary.replace(jar);executable=program/executable.name
    command=[str(executable),'--background-check','--desktop-home',str(home)]
    if mode=='init-hang':command+=['--simulate-init-hang','--background-timeout-ms','1000']
    elif mode in ['both','limit']:command+=['--background-fault',mode]
    env=dict(os.environ,DFS_JAVA_HEAP_MB='512',JAVA_TOOL_OPTIONS='-Xms64m -Xmx512m')
    log=(home.parent/'stderr.log').open('wb')
    process=subprocess.Popen(command,cwd=executable.parent,env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
    root=psutil.Process(process.pid);owned={(process.pid,root.create_time()):root}
    samples={};peak_rss=peak_private=0;start=time.perf_counter();timed_out=False
    while process.poll() is None:
        if time.perf_counter()-start>55:
            timed_out=True;break
        try:
            for child in root.children(recursive=True):owned[(child.pid,child.create_time())]=child
        except psutil.NoSuchProcess:pass
        rss=private=0
        for identity,child in list(owned.items()):
            try:
                if child.create_time()!=identity[1]:continue
                memory=child.memory_info();cpu=child.cpu_times();io=child.io_counters()
                rss+=memory.rss;private+=getattr(memory,'private',0)
                samples[identity]={'role':'webview' if 'webview' in child.name().lower() else 'java' if child.name().lower()=='java.exe' else 'desktop','cpuSeconds':cpu.user+cpu.system,'readBytes':io.read_bytes,'writeBytes':io.write_bytes}
            except (psutil.NoSuchProcess,psutil.AccessDenied):pass
        peak_rss=max(peak_rss,rss);peak_private=max(peak_private,private)
        time.sleep(.02)
    if timed_out:
        for identity,child in reversed(list(owned.items())):
            try:
                if child.create_time()==identity[1]:child.kill()
            except (psutil.NoSuchProcess,psutil.AccessDenied):pass
    process.wait(timeout=10);log.close()
    deadline=time.monotonic()+10
    lingering=[]
    while True:
        lingering=[]
        for identity,child in owned.items():
            try:
                if child.create_time()==identity[1] and child.is_running():lingering.append(identity[0])
            except psutil.NoSuchProcess:pass
        if not lingering or time.monotonic()>deadline:break
        time.sleep(.1)
    cleanup_terminated=[]
    for identity,child in owned.items():
        if identity[0] not in lingering:continue
        try:
            if child.create_time()==identity[1]:child.kill();cleanup_terminated.append(identity[0])
        except (psutil.NoSuchProcess,psutil.AccessDenied):pass
    report_path=home/'background-check.json'
    report=json.loads(report_path.read_text(encoding='utf-8')) if report_path.exists() else {'success':False,'error':'No result; possible WebView/runtime or existing single-instance conflict'}
    expected=(not report.get('success') and report.get('timeoutMillis')==1000 and process.returncode==1) if mode=='init-hang' else (report.get('success') is True and process.returncode==0 and report.get('buildId')==expected_build)
    if mode=='stale-core':expected=report.get('success') is False and process.returncode==1 and any(word in str(report.get('error','')).lower() for word in ['构建','资源','core'])
    row={'mode':mode,'passed':expected and not timed_out and not lingering,'exitCode':process.returncode,'timedOut':timed_out,'seconds':round(time.perf_counter()-start,3),'peakSummedWorkingSetMiB':round(peak_rss/1024**2,2),'peakSummedPrivateBytesMiB':round(peak_private/1024**2,2),'observedProcesses':len(owned),'lingeringOwnedPids':lingering,'cleanupTerminatedOwnedPids':cleanup_terminated,'observedProcessCounters':list(samples.values()),'report':report,'home':str(home)}
    rows.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
result={'passed':all(row['passed'] for row in rows),'samplingMillis':20,'workingSetIncludesSharedPageDoubleCounting':True,'processCountersAreSampledNotCompleteLifetimeTotals':True,'rows':rows}
(output/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(0 if result['passed'] else 1)
