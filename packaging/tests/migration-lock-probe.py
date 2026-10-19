"""Hold a real Java core task lease, then exercise Rust C-to-D migration on toy data."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import uuid

parser=argparse.ArgumentParser()
parser.add_argument('--probe',type=Path,required=True)
parser.add_argument('--jar',type=Path,required=True)
parser.add_argument('--jdk',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
token=uuid.uuid4().hex
parent=Path.home()/'AppData/Local/Temp'/('dfs-cross-volume-'+token);parent.mkdir(exist_ok=False)
(parent/'.owned-synthetic-fixture').write_text(token)
old=parent/'legacy';data=old/'management/publishing-data';(data/'keys').mkdir(parents=True)
key=b'synthetic-only-migration-identity';(data/'keys/test.pk8').write_bytes(key)
con=sqlite3.connect(data/'management.db')
con.execute('CREATE TABLE fixture (id INTEGER PRIMARY KEY,name TEXT)')
con.executemany('INSERT INTO fixture(name) VALUES (?)',[(f'synthetic-{n}',) for n in range(27)])
con.commit();con.close()
(old/'management/management-settings.json').write_text('{"dataDirectory":"publishing-data"}',encoding='utf8')
(old/'connections.json').write_text(json.dumps({'profiles':[{'id':'fixture','name':'Synthetic Java lease','kind':'local','home':str(old/'management')}]}),encoding='utf8')
target=output/('cross-volume-'+token)/'data'
worker=Path(__file__).with_name('MigrationLeaseWorker.java')
subprocess.run([str(args.jdk/'bin/javac.exe'),'-cp',str(args.jar),'-d',str(output/'classes'),str(worker)],check=True,timeout=30)
log=(output/'java-lease.log').open('wb')
lease=subprocess.Popen([str(args.jdk/'bin/java.exe'),'-Xms16m','-Xmx64m','-cp',str(output/'classes')+';'+str(args.jar),'cn.dreamingfish.updater.management.MigrationLeaseWorker',str(data)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,encoding='utf8')
try:
    assert lease.stdout.readline().strip()=='ready'
    blocked=subprocess.run([str(args.probe),str(old),str(target),'blocked'],check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',timeout=30)
    (output/'blocked.log').write_text(blocked.stdout,encoding='utf8')
finally:
    if lease.poll() is None:
        try:lease.communicate('\n',timeout=10)
        except subprocess.TimeoutExpired:lease.kill();lease.wait(timeout=10)
    log.close()
completed=subprocess.run([str(args.probe),str(old),str(target)],check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',timeout=30)
(output/'completed.log').write_text(completed.stdout,encoding='utf8')
copied=target/'management/publishing-data'
con=sqlite3.connect(copied/'management.db');count=con.execute('SELECT COUNT(*) FROM fixture').fetchone()[0];con.close()
assert count==27 and (copied/'keys/test.pk8').read_bytes()==key and not old.exists()
(output/'result.json').write_text(json.dumps({'passed':True,'actualJavaTaskLease':True,'configuredDirectory':'publishing-data','blockedUntilLeaseReleased':True,'databaseRecords':count,'keySha256':hashlib.sha256(key).hexdigest(),'source':str(old),'target':str(target)},indent=2),encoding='utf8')
print('Java-held configured root blocked migration; release then migrated 27 SQLite rows across volumes.')
