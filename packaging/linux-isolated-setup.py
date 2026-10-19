"""Run inside the existing WSL distro; create only a fresh named /tmp test root."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import tarfile
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,required=True)
parser.add_argument('--source',type=Path,required=True)
parser.add_argument('--jdk',type=Path,required=True)
parser.add_argument('--node',type=Path,required=True)
parser.add_argument('--node-sha256',required=True)
parser.add_argument('--run-script',type=Path)
parser.add_argument('--export',type=Path)
args=parser.parse_args()
root=args.root.resolve()
if not re.fullmatch(r'/tmp/dfs-updater-remediation-[a-f0-9]{32}',str(root)) or root.exists():
    raise SystemExit('Linux test root must be a new /tmp/dfs-updater-remediation-UUID directory')
root.mkdir(mode=0o700)

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def unpack(archive,destination,expected):
    if sha(archive)!=expected:raise SystemExit('Tool archive checksum differs from its verified publisher checksum')
    destination.mkdir()
    with tarfile.open(archive) as package:
        for member in package.getmembers():
            target=(destination/member.name).resolve()
            if not target.is_relative_to(destination):raise SystemExit('Archive member escapes its boundary')
            if member.issym() or member.islnk():
                linked=((target.parent if member.issym() else destination)/member.linkname).resolve()
                if not linked.is_relative_to(destination):raise SystemExit('Archive link escapes its boundary')
            member.uid=os.getuid();member.gid=os.getgid();member.uname=member.gname='';member.mode &= 0o777
        package.extractall(destination)
    return next(path for path in destination.iterdir() if path.is_dir())

source=root/'source';source.mkdir()
with zipfile.ZipFile(args.source) as package:
    for member in package.infolist():
        if not (source/member.filename).resolve().is_relative_to(source):raise SystemExit('Source archive escapes its boundary')
    package.extractall(source)
jdk=unpack(args.jdk,root/'jdk','e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370')
node=unpack(args.node,root/'node',args.node_sha256)
for directory in ['tmp','user-home','npm-cache']:(root/directory).mkdir()
(root/'settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>',encoding='utf-8')
(root/'npmrc').write_text('registry=https://registry.npmjs.org\ncache='+str(root/'npm-cache')+'\n',encoding='utf-8')
(root/'environment.json').write_text(json.dumps({'root':str(root),'source':str(source),'jdk':str(jdk),'node':str(node)}),encoding='utf-8')
print((root/'environment.json').read_text())

if args.run_script:
    result=subprocess.run(['/bin/bash',str(args.run_script),str(root)])
    if args.export:
        destination=args.export.resolve()
        destination.mkdir(parents=True,exist_ok=False)
        if (root/'logs').exists():shutil.copytree(root/'logs',destination/'logs')
        shutil.copyfile(root/'environment.json',destination/'environment.json')
        for module in ['protocol','update-engine','management-core','management-cli','bootstrap-agent','player-app']:
            reports=source/module/'target/surefire-reports'
            if reports.exists():
                target=destination/'junit'/module;target.mkdir(parents=True)
                for file in reports.glob('TEST-*.xml'):shutil.copyfile(file,target/file.name)
        (destination/'result.json').write_text(json.dumps({'success':result.returncode==0,'exitCode':result.returncode,'isolatedRoot':str(root)},indent=2),encoding='utf-8')
    raise SystemExit(result.returncode)
