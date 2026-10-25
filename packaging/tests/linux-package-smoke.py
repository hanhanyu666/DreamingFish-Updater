"""Run the packaged Linux runtime and CLI in a new /tmp fixture, exporting logs only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--archive',type=Path,required=True)
parser.add_argument('--expected',required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
root=Path('/tmp')/('dfs-updater-package-smoke-'+uuid.uuid4().hex);root.mkdir(mode=0o700)
output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
steps=[]
try:
    with zipfile.ZipFile(args.archive) as package:
        for entry in package.infolist():
            if not (root/entry.filename).resolve().is_relative_to(root):raise RuntimeError('Archive escapes its fixture')
        package.extractall(root)
    launcher=root/'dfs-admin';assert launcher.is_file()
    for executable in [launcher,*(root/'runtime/bin').glob('*'),root/'runtime/lib/jspawnhelper']:
        if executable.is_file():executable.chmod(0o755)
    with zipfile.ZipFile(root/'app/dfs-admin.jar') as jar:
        marker=json.loads(jar.read('META-INF/dreamingfish/build/management-cli.json'))
        assert marker['buildId']==args.expected
    def run(name,arguments,expected=0,environment=None):
        result=subprocess.run(arguments,cwd=root,env=environment,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
        (output/(name+'.log')).write_bytes(result.stdout)
        steps.append({'name':name,'exitCode':result.returncode,'expected':expected})
        if result.returncode!=expected:raise RuntimeError(name+' failed: '+result.stdout.decode('utf8',errors='replace'))
        return result.stdout.decode('utf8')
    run('runtime',[str(root/'runtime/bin/java'),'-version'])
    assert '0.3.1' in run('version',[str(launcher),'--version'])
    run('help',[str(launcher),'--help'])
    source=root/'synthetic-source';(source/'mods').mkdir(parents=True);(source/'config').mkdir()
    (source/'config/example.txt').write_text('synthetic fixture\n')
    with zipfile.ZipFile(source/'mods/fixture.jar','w') as jar:
        jar.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'packaged_fixture','name':'Packaged fixture','version':'1.0.0'}))
    run('create',[str(launcher),'project','create','package-smoke','--name','Isolated package smoke','--source',str(source),'--public-url','http://127.0.0.1:18099'])
    run('scan',[str(launcher),'project','scan','package-smoke'])
    published=run('publish',[str(launcher),'project','publish','package-smoke','--version','1.0.0','--minimum-player-version','0.2.1','--changelog','Synthetic package smoke','--removed-files','DELETE','--yes','--confirm-player-upgrade'])
    assert 'Published' in published
    run('tasks',[str(launcher),'tasks'])
    run('storage-preview',[str(launcher),'storage'])
    for index,invalid in enumerate(['0','999999','512 -evil','abc']):
        run('invalid-heap-'+str(index),[str(launcher),'--version'],2,dict(os.environ,DFS_JAVA_HEAP_MB=invalid))
    success=True
finally:
    archive_hash=hashlib.sha256()
    with args.archive.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):archive_hash.update(block)
    (output/'result.json').write_text(json.dumps({'success':locals().get('success',False),'buildId':args.expected,'archiveSha256':archive_hash.hexdigest(),'fixture':str(root),'steps':steps,'systemInstall':False},indent=2),encoding='utf8')
