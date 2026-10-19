"""Exercise the shipped adjustable Windows launchers in a new extracted fixture."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--archive',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
with zipfile.ZipFile(args.archive) as package:
    for entry in package.infolist():
        if not (root/entry.filename).resolve().is_relative_to(root):raise SystemExit('Archive escaped the fixture')
    package.extractall(root)
steps=[]
for kind in ['cmd','ps1']:
    for value in [None,'256','0','999999','512 -evil','abc']:
        env=dict(os.environ)
        if value is None:env.pop('DFS_JAVA_HEAP_MB',None)
        else:env['DFS_JAVA_HEAP_MB']=value
        launcher=root/('dfs-admin.'+kind)
        command=[str(launcher),'--version'] if kind=='cmd' else ['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(launcher),'--version']
        result=subprocess.run(command,cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,creationflags=subprocess.CREATE_NO_WINDOW)
        text=result.stdout.decode('utf8',errors='replace')
        passed=(result.returncode==0 and '0.2.6' in text) if value in [None,'256'] else (result.returncode!=0 and 'DFS_JAVA_HEAP_MB' in text)
        name=kind+'-'+str(len(steps));(root/(name+'.log')).write_bytes(result.stdout)
        steps.append({'launcher':kind,'heapInput':value,'exitCode':result.returncode,'passed':passed})
(root/'result.json').write_text(json.dumps({'passed':all(s['passed'] for s in steps),'steps':steps,'realDataUsed':False},indent=2),encoding='utf8')
raise SystemExit(0 if all(s['passed'] for s in steps) else 1)
