"""Reuse the earlier client/fault driver against current production implementations."""
import csv
import importlib.util
import json
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

from build import HERE, DOCKER, windows_path

spec=importlib.util.spec_from_file_location('previous_lb',HERE.parent/'lb-failover/run_lb.py')
driver=importlib.util.module_from_spec(spec);spec.loader.exec_module(driver)
driver.HERE=HERE;driver.RESULTS=HERE/'results';driver.PROJECT='ssasukae-production-recovery'
driver.BASE='http://127.0.0.1:18190';driver.STATS='http://127.0.0.1:18191/stats;csv'
A_RECOVERY=True

def compose(*args,check=True):
    env=HERE/'build/runtime.env'
    env.write_text(f'RECOVERY_ENABLED={str(driver.RECOVERY).lower()}\nA_RECOVERY_ENABLED={str(A_RECOVERY).lower()}\n')
    result=subprocess.run([DOCKER,'compose','--env-file',windows_path(env),'-f',windows_path(HERE/'compose.yaml'),*args],
                          text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    driver.write_jsonl('process-log.jsonl',{'at':driver.now(),'args':args,'exitCode':result.returncode,'output':result.stdout})
    if check and result.returncode:raise RuntimeError(result.stdout)
    return result.stdout
driver.compose=compose

def startup(a_recovery=True):
    global A_RECOVERY
    A_RECOVERY=a_recovery;driver.startup(True)
    for service in ['a','b']:
        env=dict(v.split('=',1) for v in driver.inspect(service)['Config']['Env'] if '=' in v)
        assert env['CARD_RECOVERY_ENABLED']==str(a_recovery if service=='a' else True).lower()
        assert env['WEBSOCKET_RELAY_ENABLED']=='true'

class CrossProbe:
    def __init__(self,fixture):
        self.items=[];self.messages=queue.Queue();self.errors=[]
        self.proc=subprocess.Popen([DOCKER,'run','--rm','-i','--network',driver.PROJECT+'_default',
            'ssasukae-production-verification:local','java','-cp','/runtime/classes:/runtime/lib/*','experiment.CrossNodeProbe'],
            text=True,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=1)
        def read():
            for line in self.proc.stdout:
                if line.startswith('CROSS:'):
                    item=json.loads(line[6:]);self.items.append(item);self.messages.put(item)
        def errors():
            for line in self.proc.stderr:self.errors.append(line)
        threading.Thread(target=read,daemon=True).start();threading.Thread(target=errors,daemon=True).start()
        self.proc.stdin.write(json.dumps({'url':'ws://lb:8080/ws','roomId':fixture['roomId'],'token':fixture['token']})+'\n')
        self.proc.stdin.flush()
        self.wait(lambda m:m['kind']=='ready')
        identities=[i for i in self.items if i['kind']=='identity']
        assert identities and identities[-1]['value']['instance']=='A',identities
    def wait(self,predicate,timeout=15):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            try:
                item=self.messages.get(timeout=.1)
                if predicate(item):return item
            except queue.Empty:
                if self.proc.poll() is not None:raise RuntimeError(''.join(self.errors)[-2000:])
        raise TimeoutError(str(self.items[-5:])+''.join(self.errors)[-1000:])
    def close(self):
        self.proc.stdin.write('stop\n');self.proc.stdin.flush()
        try:self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:self.proc.kill()

def secondary(path,token):
    primary=driver.BASE;driver.BASE='http://127.0.0.1:18192'
    try:
        value,_=driver.request(path,'POST',token);assert value['sourceNode']=='B';return value
    finally:driver.BASE=primary

def cross_node():
    startup(False)
    for iteration in range(11):
        fixture,_=driver.request('/experiment/fixture?duration=2','POST')
        room=fixture['roomId'];probe=CrossProbe(fixture)
        try:
            if iteration==0:
                for sequence in range(100):
                    for personal in ['false','true']:
                        secondary(f'/experiment/relay-test/{room}?sequence={sequence}&personal={personal}',fixture['token'])
                probe.wait(lambda m:m['kind']=='event' and m['event']['eventType']=='PRIVATE_RELAY_PROBE'
                           and m['event']['payload']['sequence']==99)
                time.sleep(.2)
                for kind in ['ROOM_RELAY_PROBE','PRIVATE_RELAY_PROBE']:
                    events=[i['event'] for i in probe.items if i['kind']=='event' and i['event']['eventType']==kind]
                    assert [e['payload']['sequence'] for e in events]==list(range(100))
                    assert all(e['payload']['sourceNode']=='B' for e in events)
                result={'case':'LIVE_ROOM_AND_PRIVATE','roomMessages':100,'privateMessages':100,'outcome':'PASS'}
            else:
                seeded=secondary(f'/experiment/seed-pending/{room}',fixture['token'])
                probe.wait(lambda m:m['kind']=='event' and m['event']['eventType']=='CARD_EFFECT_ENDED')
                time.sleep(.1)
                events=[i['event']['eventType'] for i in probe.items if i['kind']=='event']
                assert events.count('CARD_EFFECT_STARTED')==events.count('CARD_EFFECT_ENDED')==1,events
                terminal=[e for e in driver.room_events(room) if e['event']['eventType'] in ['CARD_EFFECT_STARTED','CARD_EFFECT_ENDED']]
                assert len(terminal)==2 and all(e['instance']=='B' for e in terminal),terminal
                final,_=driver.request(f'/experiment/lb/snapshot/{room}',token=fixture['token'])
                assert final['snapshot'].get('activeCard') is None
                assert final['snapshot']['myCard']['status']=='USED',final['snapshot']['myCard']
                result={'case':'LIVE_CARD_FROM_B_TO_A','iteration':iteration,'outcome':'PASS','finalCard':'NONE'}
            assert not any(i['kind']=='transportError' for i in probe.items)
            assert driver.inspect('a')['State']['Running'] and driver.inspect('b')['State']['Running']
            driver.write_jsonl('cross-node-results.jsonl',result)
            print(json.dumps(result),flush=True)
        finally:
            driver.write_jsonl('cross-node-traces.jsonl',{'roomId':room,'messages':probe.items})
            probe.close()

def failover(repetitions):
    startup(True);probe=driver.Probe()
    matrix=driver.RESULTS/'fault-matrix.csv'
    try:
        with matrix.open('w',newline='') as out:
            writer=csv.DictWriter(out,driver.FIELDS);writer.writeheader()
            for case in ['ACTIVE','PENDING','LATE_RECONNECT']:
                for iteration in range(1,repetitions+1):
                    row=driver.trial(probe,'production',case,iteration)
                    writer.writerow(row);out.flush()
                    print(json.dumps({k:row[k] for k in ['case','iteration','outcome','reconnectMs','serverEnds','clientEnds']}),flush=True)
    finally:probe.close()

def cold_restart():
    startup(True);probe=driver.Probe()
    try:
        for case in ['PENDING','ACTIVE']:
            if not driver.inspect('a')['State']['Running']:compose('up','-d','a')
            driver.wait_primary()
            fixture,_=driver.request('/experiment/fixture?duration=6','POST')
            room=fixture['roomId'];perf=fixture['performanceId'];token=fixture['token']
            probe.send('start',base='http://lb:8080',roomId=room,performanceId=perf,token=token,reconnectDelayMs=3000)
            probe.wait(lambda m:m['kind']=='connected' and m.get('roomId')==room)
            probe.send('activate')
            pending=probe.wait(lambda m:m['kind']=='event' and m.get('roomId')==room
                               and m['event']['eventType']=='CARD_ACTIVATION_SCHEDULED')
            deadline=pending['event']['payload']['activateAt']
            if case=='ACTIVE':
                started=probe.wait(lambda m:m['kind']=='event' and m.get('roomId')==room
                                   and m['event']['eventType']=='CARD_EFFECT_STARTED')
                deadline=started['event']['payload']['endsAt']
            compose('kill','-s','SIGKILL','a','b')
            assert all(not driver.inspect(node)['State']['Running'] for node in ['a','b'])
            killed={node:driver.inspect(node)['State'] for node in ['a','b']}
            assert all(state['ExitCode']==137 for state in killed.values())
            if case=='PENDING':
                assert all(driver.seconds(driver.inspect(node)['State']['FinishedAt'])<driver.seconds(deadline) for node in ['a','b'])
            probe.stop()
            # Redis snapshots outlive both JVMs. Only B is restarted, after the deadline.
            driver.wait_until(driver.seconds(deadline)+1,[])
            compose('up','-d','--no-deps','b')
            until=time.monotonic()+90
            while time.monotonic()<until:
                try:
                    final,_=driver.request(f'/experiment/lb/snapshot/{room}',token=token)
                    if final['instance']=='B' and final['snapshot'].get('activeCard') is None:break
                except OSError:pass
                time.sleep(.2)
            else:raise TimeoutError('Cold restart did not reconcile card')
            assert not driver.inspect('a')['State']['Running']
            events=driver.room_events(room)
            types=[e['event']['eventType'] for e in events]
            if case=='PENDING':
                assert types.count('CARD_ACTIVATION_CANCELLED')==1 and types.count('CARD_EFFECT_STARTED')==0
                assert final['snapshot']['myCard']['status']=='ASSIGNED'
                terminal=next(e for e in events if e['event']['eventType']=='CARD_ACTIVATION_CANCELLED')
                assert terminal['event']['payload']['cancelReason']=='SYSTEM_CANCELLED'
            else:
                assert types.count('CARD_EFFECT_STARTED')==types.count('CARD_EFFECT_ENDED')==1
                assert final['snapshot']['myCard']['status']=='USED'
            assert types.count('CARD_ACTIVATION_SCHEDULED')==1
            result={'case':'COLD_'+case,'roomId':room,'outcome':'PASS','finalCard':'NONE',
                    'assignment':final['snapshot']['myCard']['status'],'deadline':deadline}
            driver.write_jsonl('cold-traces.jsonl',{'case':case,'roomId':room,'deadline':deadline,
                'killed':killed,'final':final,'events':events,'aStillDead':True})
            driver.write_jsonl('cold-results.jsonl',result)
            print(json.dumps(result),flush=True)
    finally:probe.close()

if __name__=='__main__':
    try:
        if sys.argv[1]=='cross':cross_node()
        elif sys.argv[1]=='cold':cold_restart()
        elif sys.argv[1]=='start':startup(True)
        elif sys.argv[1]=='down':compose('down')
        else:failover(int(sys.argv[2]) if len(sys.argv)>2 else 10)
    finally:
        if sys.argv[1]!='down':
            (driver.RESULTS/'production-services.txt').write_text(compose('logs','--no-color',check=False))
