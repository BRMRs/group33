#!/usr/bin/env python3
"""Verify the preserved, explicitly scoped PR37/PR119 evidence without network access."""
from pathlib import Path
import hashlib,json,re,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
P=ROOT/'docs/evidence/s2-review-20261121'
def read(path):return json.loads(path.read_text())
def completed(path,expected):
 text=path.read_text();items=[];pending={}
 for line in text.splitlines():
  if line.startswith('INSTRUMENTATION_STATUS: '):
   key,sep,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=')
   if sep:pending[key]=value
  elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
   code=int(line.rsplit(': ',1)[1])
   if code!=1 and 'test' in pending:items.append((pending.get('class'),pending['test'],code))
   pending={}
 assert len(items)==expected and len({x[:2] for x in items})==expected and all(x[2]==0 for x in items),path
 assert re.search(r'^OK \('+str(expected)+r' tests?\)$',text,re.M) and 'FAILURES!!!' not in text,path
 return len(items)
def main():
 names=[]
 for line in (P/'SHA256SUMS').read_text().splitlines():
  digest,name=line.split('  ',1);relative=Path(name);assert not relative.is_absolute() and '..' not in relative.parts
  assert name not in names and re.fullmatch(r'[0-9a-f]{64}',digest);names.append(name)
  assert hashlib.sha256((P/name).read_bytes()).hexdigest()==digest,name
 assert set(names)=={x.relative_to(P).as_posix() for x in P.rglob('*') if x.is_file() and x.name!='SHA256SUMS'}
 m=read(P/'packet.json');local=read(P/'pr37/focused-tests.json')
 assert m['pr37_tested_head']=='c45c856a1bacdcf5e55ef5e0c98713d3a7a3da6e' and local['head']==m['pr37_tested_head'] and local['exit']==0
 total=0
 for mode in ['Debug','Release']:
  files=sorted((P/'pr37'/mode).glob('TEST-*.xml'));assert len(files)==2
  for f in files:
   root=ET.parse(f).getroot();expected=33 if root.get('name','').endswith('.S1PolicyTest') else 5
   assert root.get('name','').endswith(('.S1PolicyTest','.CommandRecoveryPolicyTest'))
   assert int(root.get('tests',0))==expected and len(root.findall('testcase'))==expected
   assert all(int(root.get(k,0))==0 for k in ['failures','errors','skipped']);total+=expected
  assert local['reports'][mode]==dict(tests=38,failures=0,errors=0,skipped=0)
 log=(P/'pr37/execution.txt').read_text()
 for task in ['testDebugUnitTest','testReleaseUnitTest']:assert re.search(r'^> Task :android-app:'+task+r'$',log,re.M)
 assert 'BUILD SUCCESSFUL in 44s' in log and '63 actionable tasks: 49 executed, 14 from cache' in log
 D=P/'pr119';r=read(D/'result.json');assert r['head']==m['pr119_tested_head']=='1308bc59f18305dd6fe2083679ea001c858e0bf9'
 assert r['api']=='35' and r['abi']=='x86_64' and r['passed'] and r['emulator_stopped']
 count=completed(D/'instrumentation.log',13)+completed(D/'dispatch-recovery/room-worker.log',8)+completed(D/'dispatch-recovery/verify-new-process.log',1)+completed(D/'automatic-recovery/verify-new-process.log',1)
 a=read(D/'automatic-recovery/result.json');b=read(D/'postgres-recovery/result.json');assert a==r['automatic_recovery'] and b==r['postgres_recovery']
 assert a['passed'] and a['automatic_startup_end_to_end'] and a['lease_clock']=='real clock' and not a['production_server'] and a['receiver_stopped']
 q=a['process_probe'];before=read(D/'automatic-recovery/original-action.json');ledger=read(D/'automatic-recovery/receiver-ledger.json')
 assert q['manualRecoveryCalls']==0 and q['observedRetry'] and q['elapsedClaimMillis']==105038 and q['originalDeadlinePreserved']
 assert q['oldPid']==before['pid'] and q['oldPid']!=q['newPid'] and q['workId']==before['workId']
 assert q['originalRunAttemptCount']==before['claimedRunAttemptCount']==1 and q['attempts']==2 and q['state']=='ACKNOWLEDGED'
 assert any(x['state']=='ENQUEUED' and x['actionState']=='DISPATCHING' and x['runAttemptCount']>q['originalRunAttemptCount'] for x in q['workObservations'])
 assert q['workObservations'][-1]==dict(state='SUCCEEDED',runAttemptCount=4,actionState='ACKNOWLEDGED')
 assert len(ledger['requests'])==2 and len(ledger['effects'])==1
 assert ledger['requests'][0]['sha256']==ledger['requests'][1]['sha256'] and ledger['requests'][0]['payload']==ledger['requests'][1]['payload']
 assert ledger['requests'][0]['actionId']==q['actionId']
 for folder in ['dispatch-recovery','automatic-recovery']:
  l=read(D/folder/'receiver-ledger.json');assert len(l['requests'])==2 and len(l['effects'])==1 and l['requests'][0]['payload']==l['requests'][1]['payload']
 assert b['passed'] and b['real_server'] and b['real_postgres'] and not b['memory_store'] and not b['receiver_mock']
 assert len(b['checks'])==62 and all(x['passed'] for x in b['checks']) and b['business_rows']==dict(occurrences=2,events=2,operations=2)
 assert b['original_receipt_immutable'] and b['current_facts_not_overwritten'] and b['isolated_schema_removed'] and b['private_fixture_removed']
 assert b['server_processes_stopped'] and b['response_proxy_stopped'] and len(b['server_pids'])==2 and len(set(b['server_pids']))==2
 assert re.fullmatch(r'[0-9a-f]{64}',b['boot_jar_sha256'])
 assert hashlib.sha256((D/'notification-feedback.png').read_bytes()).hexdigest()==r['notification_screenshot_sha256']
 assert r['automatic_test_apk_sha256']!=r['sha256']['android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk']
 for n,blob_count,manifest_count in [(37,449,400),(119,476,427)]:
  source=read(P/f'pr{n}/source-verification.json');assert source['head']==m[f'pr{n}_tested_head'] and source['verified_blobs']==blob_count and source['manifest_entries']==manifest_count and source['policy'] and source['all_git_blob_and_tree_hashes_verified']
 comments=read(P/'audit/manual-publication-verification.json')
 for n in [37,119]:assert comments[str(n)]['author']=='XinyanXue' and comments[str(n)]['head']==m[f'pr{n}_tested_head'] and comments[str(n)]['remote_readback_verified']
 print(json.dumps(dict(packet_files=len(names),local_variant_reports=total,api35_report_instances=count,real_postgres_assertions=62,native_retry_millis=q['elapsedClaimMillis'],manual_recovery_calls=0,continuous_android_to_real_server_trace=False),indent=2))
if __name__=='__main__':main()
