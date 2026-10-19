"""Measure complete directory derivation and necessary current-content reads separately."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import threading
import time
import psutil

parser=argparse.ArgumentParser()
parser.add_argument('--baseline-source',type=Path,required=True)
parser.add_argument('--baseline-jar',type=Path,required=True)
parser.add_argument('--current-jar',type=Path,required=True)
parser.add_argument('--jdk',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--scratch',type=Path,required=True)
parser.add_argument('--proof-only',action='store_true',help='Keep existing directory evidence and run only content-read measurements')
args=parser.parse_args()
repo=Path(__file__).resolve().parents[1]
output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
scratch=args.scratch.resolve();scratch.mkdir(parents=True,exist_ok=False)
def measured(command,lines):
    child=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf8',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    process=psutil.Process(child.pid);stop=threading.Event();peaks=[0,0]
    def sample():
        while not stop.is_set():
            try:
                memory=process.memory_info();peaks[0]=max(peaks[0],memory.rss);peaks[1]=max(peaks[1],getattr(memory,'private',0))
            except psutil.NoSuchProcess:break
            stop.wait(.01)
    sampler=threading.Thread(target=sample,daemon=True);sampler.start()
    try:
        records=[json.loads(child.stdout.readline()) for _ in range(lines)]
        cpu=process.cpu_times();io=process.io_counters()
        counters={'peakWorkingSetMiB':peaks[0]/1024**2,'peakPrivateBytesMiB':peaks[1]/1024**2,'lifetimeCpuSeconds':cpu.user+cpu.system,'readMiB':io.read_bytes/1024**2,'writeMiB':io.write_bytes/1024**2}
        _,errors=child.communicate('\n',timeout=10)
        if child.returncode:raise RuntimeError(errors)
        return records,counters
    finally:
        stop.set();sampler.join()
        if child.poll() is None:child.kill();child.wait(timeout=10)
directory=[]
for number in ([] if args.proof_only else range(1,4)):
    variants=[('baseline',args.baseline_source/'admin-desktop/src/workbench/model.ts'),('current',repo/'admin-desktop/src/workbench/model.ts')]
    if number%2==0:variants.reverse()
    for variant,model in variants:
        records,counters=measured(['node',str(repo/'packaging/directory-benchmark.mjs'),variant,str(model.resolve())],1)
        directory.append({'round':number,**records[0],**counters})
        print(json.dumps(directory[-1]),flush=True)
if directory:
    assert len({r['resultSha256'] for r in directory})==1,'Directory output changed between variants'
    (output/'directory-raw.json').write_text(json.dumps(directory,indent=2),encoding='utf8')
medians=[]
for variant in ['baseline','current']:
    rows=[row for row in directory if row['variant']==variant]
    if not rows:continue
    medians.append({'variant':variant,**{key:statistics.median(row[key] for row in rows) for key in ['seconds','cpuSeconds','peakWorkingSetMiB','peakPrivateBytesMiB','readMiB','writeMiB']}})
(output/'directory-medians.json').write_text(json.dumps(medians,indent=2),encoding='utf8')
content=scratch/'content.bin'
with content.open('wb') as stream:
    block=bytes(range(256))*4096
    for _ in range(256):stream.write(block)
proof=[]
for variant,jar in [('baseline',args.baseline_jar),('current',args.current_jar)]:
    engine=(args.baseline_source if variant=='baseline' else repo)/'update-engine/target/classes'
    if not engine.is_dir():raise RuntimeError('Build update-engine before the content proof measurement')
    classpath=str(engine.resolve())+';'+str(jar.resolve())
    classes=scratch/variant/'classes';classes.mkdir(parents=True)
    compile_result=subprocess.run([str(args.jdk/'bin/javac.exe'),'-cp',classpath,'-d',str(classes),str(repo/'packaging/content-proof-benchmark.java')],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
    (output/(variant+'-javac.log')).write_bytes(compile_result.stdout)
    if compile_result.returncode:raise RuntimeError('Content proof helper compilation failed')
    records,counters=measured([str(args.jdk/'bin/java.exe'),'-Xms64m','-Xmx512m','-cp',str(classes)+';'+classpath,'ContentProofBenchmark',str(scratch),variant+'-index.json'],3)
    proof.append({'variant':variant,'rounds':records,'osCountersIncludingTwoWarmupHashes':counters,'necessaryContentReadsPerRoundBytes':0 if variant=='baseline' else 256*1024**2})
(output/'proof-raw.json').write_text(json.dumps({'fixtureSha256':hashlib.file_digest(content.open('rb'),'sha256').hexdigest(),'rows':proof},indent=2),encoding='utf8')
print('Directory and content-read evidence: '+str(output),flush=True)
