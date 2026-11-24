"""HAProxy-only HTTP/STOMP failover, with no published backend ports and real SIGKILL."""
import argparse
from collections import Counter
import csv
from datetime import datetime,timezone
import io
import json
import queue
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from build import HERE,DOCKER,windows_path

RESULTS=HERE/'results'
BASE='http://127.0.0.1:18090'
STATS='http://127.0.0.1:18091/stats;csv'
PROJECT='ssasukae-lb-failover'
RECOVERY=True

def now():return datetime.now(timezone.utc).isoformat()
def seconds(value):return datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()
def write_jsonl(name,value):
    with (RESULTS/name).open('a') as out:out.write(json.dumps(value,ensure_ascii=False)+'\n')

def compose(*args,check=True):
    env=HERE/'build/runtime.env'
    env.write_text('RECOVERY_ENABLED='+str(RECOVERY).lower()+'\n')
    started=now()
    result=subprocess.run([DOCKER,'compose','--env-file',windows_path(env),'-f',windows_path(HERE/'compose.yaml'),*args],
                          text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    write_jsonl('process-log.jsonl',{'at':started,'finishedAt':now(),'args':args,'recovery':RECOVERY,
                                  'exitCode':result.returncode,'output':result.stdout})
    if check and result.returncode:raise RuntimeError(result.stdout)
    return result.stdout

def inspect(service):
    return json.loads(subprocess.check_output([DOCKER,'inspect',f'{PROJECT}-{service}-1'],text=True))[0]

def request(path,method='GET',token=None):
    headers={'Authorization':'Bearer '+token} if token else {}
    req=urllib.request.Request(BASE+path,headers=headers,method=method,data=b'' if method=='POST' else None)
    with urllib.request.urlopen(req,timeout=5) as response:
        body=response.read()
        return (json.loads(body) if body else None),response.headers.get('X-Backend-Instance')

def health():
    with urllib.request.urlopen(STATS,timeout=2) as response:lines=response.read().decode().splitlines()
    lines[0]=lines[0].lstrip('# ')
    rows=csv.DictReader(lines)
    return {r['svname']:r['status'] for r in rows if r['pxname']=='apps' and r['svname'] in ['a','b']}

def wait_primary(timeout=90):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        try:
            state=health()
            node,header=request('/experiment/lb/node')
            if state=={'a':'UP','b':'UP'} and node['instance']=='A' and header=='a':return
        except (OSError,ValueError):pass
        time.sleep(.1)
    raise TimeoutError('Both backends UP and primary A routing not established')

def startup(recovery):
    global RECOVERY
    RECOVERY=recovery
    # Establish the schema on A before starting B on a fresh DB.
    compose('up','-d','mysql','redis','a')
    until=time.monotonic()+120
    while time.monotonic()<until:
        log=compose('logs','--no-color','--tail','12','a')
        if 'LB_APPLICATION_READY instance=A' in log:break
        time.sleep(1)
    else:raise TimeoutError('A startup failed')
    compose('up','-d','b','lb')
    wait_primary()
    request('/experiment/lb/seed','POST')
    configurations={}
    for service in ['a','b']:
        data=inspect(service)
        env=dict(v.split('=',1) for v in data['Config']['Env'] if '=' in v)
        assert env['EXPERIMENT_RECOVERY_ENABLED']==str(recovery).lower()
        assert not data['HostConfig'].get('PortBindings'), 'Backend ports must not be published'
        configurations[service]={'imageId':data['Image'],'recovery':env['EXPERIMENT_RECOVERY_ENABLED'],
            'instance':env['INSTANCE'],'publishedPorts':data['HostConfig'].get('PortBindings')}
    write_jsonl('verified-configurations.jsonl',{'at':now(),'recovery':recovery,'backends':configurations})

class Probe:
    def __init__(self):
        self.items=[];self.messages=queue.Queue();self.errors=[]
        self.proc=subprocess.Popen([DOCKER,'run','--rm','-i','--name',PROJECT+'-probe','--network',PROJECT+'_default',
            'ssasukae-lb-experiment:local','java','-cp','/runtime/classes:/runtime/lib/*','experiment.FailoverProbe'],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
        def reader():
            for line in self.proc.stdout:
                if line.startswith('LBPROBE:'):
                    item=json.loads(line[len('LBPROBE:'):]);self.items.append(item);self.messages.put(item)
        threading.Thread(target=reader,daemon=True).start()
        def stderr():
            for line in self.proc.stderr:self.errors.append(line)
        threading.Thread(target=stderr,daemon=True).start()
    def send(self,op,**values):
        self.proc.stdin.write(json.dumps({'op':op,**values})+'\n');self.proc.stdin.flush()
    def wait(self,predicate,timeout=45):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            try:
                item=self.messages.get(timeout=.1)
                if predicate(item):return item
            except queue.Empty:
                if self.proc.poll() is not None:raise RuntimeError('Probe exited: '+''.join(self.errors)[-2000:])
        raise TimeoutError('Probe message timeout: '+''.join(self.errors)[-1000:])
    def stop(self):
        self.send('stop');self.wait(lambda m:m['kind']=='stopped',5)
    def close(self):
        if self.proc.poll() is None:
            try:self.send('exit');self.proc.wait(timeout=5)
            except (OSError,subprocess.TimeoutExpired):self.proc.kill();self.proc.wait()
        subprocess.run([DOCKER,'rm','-f',PROJECT+'-probe'],text=True,capture_output=True)

FIELDS=['phase','case','iteration','roomId','performanceId','recoveryEnabled','initialNode','reconnectedNode',
        'killRequestedAt','killFinishedAt','disconnectedAt','healthDownAt','reconnectedAt','activateAt','endsAt',
        'disconnectMs','healthDownMs','reconnectMs','snapshotAndSubscriptionRestored','snapshotState',
        'snapshotRemainingMs','playbackPositionErrorMs','serverStarts','serverEnds','serverCancels',
        'clientStarts','clientEnds','finalServerCard','finalClientCard','duplicateServerEvents','endDelayMs','outcome']

def room_events(room_id):
    path=RESULTS/'server-events.jsonl'
    if not path.exists():return []
    return [e for line in path.read_text().splitlines() if (e:=json.loads(line))['event']['roomId']==room_id]

def wait_until(deadline,health_samples):
    while time.time()<deadline:
        try:health_samples.append({'at':now(),'states':health()})
        except OSError:pass
        time.sleep(min(.05,max(.001,deadline-time.time())))

def trial(probe,phase,case,iteration,duration=6):
    if not inspect('a')['State']['Running']:compose('up','-d','--no-deps','a')
    wait_primary()
    for attempt in range(5):
        try:fixture,_=request('/experiment/fixture?duration='+str(duration),'POST');break
        except urllib.error.HTTPError as error:
            detail=compose('logs','--no-color','--since','5s','a')
            if error.code!=500 or 'Duplicate entry' not in detail or attempt==4:raise
    room=fixture['roomId'];perf=fixture['performanceId'];token=fixture['token']
    delay=8000 if case=='LATE_RECONNECT' else 3000  # Matches the existing frontend for ordinary cases.
    mark=len(probe.items)
    row=dict.fromkeys(FIELDS,'');row.update(phase=phase,case=case,iteration=iteration,roomId=room,
        performanceId=perf,recoveryEnabled=RECOVERY)
    health_samples=[];states=[]
    try:
        probe.send('start',base='http://lb:8080',roomId=room,performanceId=perf,token=token,reconnectDelayMs=delay)
        initial=probe.wait(lambda m:m['kind']=='connected' and m.get('roomId')==room)
        assert initial['wsNode']=='A' and initial['snapshotNode']=='A'
        assert initial['authenticatedUser']==str(fixture['userId']) and initial['roomSubscriptionProven']
        row['initialNode']='A';states.append({'stage':'initial','value':initial})
        try:request('/experiment/lb/snapshot/'+str(room),token='invalid-token')
        except urllib.error.HTTPError as error:assert error.code==401
        else:raise AssertionError('Unauthenticated snapshot was accepted')
        probe.send('activate')
        pending=probe.wait(lambda m:m['kind']=='event' and m.get('roomId')==room
            and m['event']['eventType']=='CARD_ACTIVATION_SCHEDULED')
        row['activateAt']=pending['event']['payload']['activateAt']
        if case=='PENDING':before=pending
        else:
            # Observe server state independently of live broker delivery. With two simple brokers,
            # B can win the start transition while the initial client is still connected to A.
            until=time.monotonic()+8
            while time.monotonic()<until:
                before,_=request('/experiment/lb/snapshot/'+str(room),token=token)
                current=before['snapshot'].get('activeCard')
                if current and current['roomCardStatus']=='ACTIVE':
                    row['endsAt']=current['endsAt'];break
                time.sleep(.1)
            else:raise TimeoutError('Server did not enter ACTIVE')
        states.append({'stage':'before_kill','value':before})
        healthy=health();health_samples.append({'at':now(),'states':healthy})
        assert healthy=={'a':'UP','b':'UP'},'B must be healthy before killing A'
        row['killRequestedAt']=now()
        compose('kill','-s','SIGKILL','a')
        killed=inspect('a')['State'];assert killed['ExitCode']==137
        row['killFinishedAt']=killed['FinishedAt'];states.append({'stage':'killed','process':killed})
        if case=='PENDING':assert seconds(row['killFinishedAt'])<seconds(row['activateAt'])
        if case=='LATE_RECONNECT':
            wait_until(seconds(row['endsAt'])+.25,health_samples)
            probe.send('status')
            stale=probe.wait(lambda m:m['kind']=='status' and m.get('roomId')==room,5)
            assert stale['activeCard'] is not None or stale['pendingCard'] is not None,'Expected stale local card before late snapshot'
            states.append({'stage':'offline_after_deadline','value':stale})
        until=time.monotonic()+40
        reconnected=None
        while time.monotonic()<until:
            sample={'at':now(),'states':health()};health_samples.append(sample)
            if sample['states'].get('a','').startswith('DOWN') and not row['healthDownAt']:
                row['healthDownAt']=sample['at']
            matches=[m for m in probe.items[mark:] if m['kind']=='connected' and m.get('roomId')==room and m['connection']>=2]
            if matches:reconnected=matches[-1];break
            time.sleep(.05)
        if reconnected is None:raise TimeoutError('No automatic reconnection through HAProxy')
        assert reconnected['wsNode']=='B' and reconnected['snapshotNode']=='B'
        assert reconnected['authenticatedUser']==str(fixture['userId']) and reconnected['roomSubscriptionProven']
        assert not inspect('a')['State']['Running'],'A must remain dead during takeover'
        row['reconnectedNode']='B';row['reconnectedAt']=reconnected['at']
        row['snapshotAndSubscriptionRestored']=True
        states.append({'stage':'reconnected_snapshot','value':reconnected})
        snap=reconnected['snapshot'];original=initial['snapshot']
        assert snap['performance']['performanceId']==perf and snap['performance']['status']=='PLAYING'
        assert sorted(p['participantId'] for p in snap['participants'])==sorted(p['participantId'] for p in original['participants'])
        expected=(seconds(snap['serverNow'])-seconds(snap['performance']['startedAt']))*1000
        row['playbackPositionErrorMs']=round(abs(snap['playback']['playbackPositionMs']-expected))
        assert row['playbackPositionErrorMs']<=2,'Playback position restarted or changed'
        card=snap.get('activeCard')
        row['snapshotState']=card['roomCardStatus'] if card else 'NONE'
        if case=='LATE_RECONNECT':assert card is None,'Late snapshot must remove expired effect'
        elif card:
            if card.get('endsAt'):
                if row['endsAt']:assert seconds(card['endsAt'])==seconds(row['endsAt']),'Effect duration was reset'
                row['endsAt']=card['endsAt']
                row['snapshotRemainingMs']=round((seconds(card['endsAt'])-seconds(snap['serverNow']))*1000)
        if not row['endsAt']:
            until=time.monotonic()+5
            while time.monotonic()<until:
                starts=[e for e in room_events(room) if e['event']['eventType']=='CARD_EFFECT_STARTED']
                if starts:row['endsAt']=starts[0]['event']['payload']['endsAt'];break
                time.sleep(.05)
        assert row['endsAt']
        wait_until(max(seconds(row['endsAt'])+1,time.time()+.2),health_samples)
        final,header=request('/experiment/lb/snapshot/'+str(room),token=token)
        assert final['instance']=='B' and header=='b'
        states.append({'stage':'final_snapshot','value':final})
        probe.send('status');local=probe.wait(lambda m:m['kind']=='status' and m.get('roomId')==room,5)
        states.append({'stage':'final_client','value':local})
        events=room_events(room);counts=Counter(e['event']['eventType'] for e in events)
        row['serverStarts']=counts['CARD_EFFECT_STARTED'];row['serverEnds']=counts['CARD_EFFECT_ENDED']
        row['serverCancels']=counts['CARD_ACTIVATION_CANCELLED']
        row['duplicateServerEvents']=sum(max(0,n-1) for n in counts.values())
        items=[m for m in probe.items[mark:] if m.get('roomId')==room]
        received=Counter(m['event']['eventType'] for m in items if m['kind']=='event')
        row['clientStarts']=received['CARD_EFFECT_STARTED'];row['clientEnds']=received['CARD_EFFECT_ENDED']
        row['finalServerCard']='ACTIVE' if final['snapshot'].get('activeCard') else 'NONE'
        row['finalClientCard']='ACTIVE' if local.get('activeCard') else 'NONE'
        disconnected=[m for m in items if m['kind']=='disconnected']
        assert disconnected
        row['disconnectedAt']=disconnected[0]['at']
        down_samples=[s for s in health_samples if s['states'].get('a','').startswith('DOWN')]
        if down_samples:row['healthDownAt']=down_samples[0]['at']
        for field,target in [('disconnectMs','disconnectedAt'),('healthDownMs','healthDownAt'),('reconnectMs','reconnectedAt')]:
            if row[target]:row[field]=round((seconds(row[target])-seconds(row['killRequestedAt']))*1000)
        if RECOVERY:
            assert row['finalServerCard']==row['finalClientCard']=='NONE'
            assert (row['serverStarts'],row['serverEnds'],row['serverCancels'],row['duplicateServerEvents'])==(1,1,0,0)
            if case=='LATE_RECONNECT':assert row['clientEnds']==0,'Terminal event should be inside intentional offline gap'
            else:assert row['clientEnds']==1,'Reconnected room subscriber missed CARD_EFFECT_ENDED'
            ending=next(e for e in events if e['event']['eventType']=='CARD_EFFECT_ENDED')
            assert ending['instance']=='B'
            row['endDelayMs']=round((seconds(ending['observedAt'])-seconds(row['endsAt']))*1000)
            row['outcome']='PASS'
        else:
            assert row['finalServerCard']==row['finalClientCard']=='ACTIVE'
            assert row['serverEnds']==0
            row['outcome']='EXPECTED_STUCK_WITH_LB_ONLY'
        return row
    finally:
        write_jsonl('state-traces.jsonl',{'phase':phase,'case':case,'iteration':iteration,'roomId':room,'states':states,'health':health_samples})
        write_jsonl('client-traces.jsonl',{'phase':phase,'case':case,'iteration':iteration,'roomId':room,
            'messages':[m for m in probe.items[mark:] if m.get('roomId')==room]})
        probe.stop()
        # Remove only this completed fixture after preserving evidence; cleanup is not counted as recovery.
        compose('exec','-T','redis','redis-cli','UNLINK',f'room:{room}:active-card',
            f'room:{room}:performance:{perf}:cards',f'performance:session:{perf}',f'performance:room:{room}:active',
            f"auth:activeSession:{fixture['userId']}",check=False)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--phase',required=True)
    parser.add_argument('--recovery',choices=['on','off'],default='on')
    parser.add_argument('--cases',nargs='+',default=['ACTIVE','PENDING','LATE_RECONNECT'])
    parser.add_argument('--repetitions',type=int,default=10)
    parser.add_argument('--keep',action='store_true')
    args=parser.parse_args()
    matrix=RESULTS/'fault-matrix.csv'
    if not matrix.exists():
        with matrix.open('w',newline='') as out:csv.DictWriter(out,FIELDS).writeheader()
    probe=None
    try:
        startup(args.recovery=='on');probe=Probe()
        for case in args.cases:
            for iteration in range(1,args.repetitions+1):
                row=trial(probe,args.phase,case,iteration)
                with matrix.open('a',newline='') as out:csv.DictWriter(out,FIELDS).writerow(row)
                print(json.dumps({k:row[k] for k in ['phase','case','iteration','outcome','reconnectMs','snapshotState','serverEnds','clientEnds']}),flush=True)
    except BaseException as error:
        write_jsonl('errors.jsonl',{'at':now(),'phase':args.phase,'type':type(error).__name__,'message':str(error)})
        raise
    finally:
        if probe:probe.close()
        (RESULTS/f'{args.phase}-services.txt').write_text(compose('logs','--no-color',check=False))
        if not args.keep:compose('down',check=False)

if __name__=='__main__':main()
