"""Cross-check final CSV outcomes against protocol messages and server session/event journals."""
from collections import Counter,defaultdict
import csv
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

HERE=Path(__file__).resolve().parent
RESULTS=HERE/'results'
def seconds(value):return datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()

def main():
    rows=list(csv.DictReader((RESULTS/'fault-matrix.csv').open()))
    traces={}
    for line in (RESULTS/'state-traces.jsonl').read_text().splitlines():
        item=json.loads(line);traces[str(item['roomId'])]=item
    clients={}
    for line in (RESULTS/'client-traces.jsonl').read_text().splitlines():
        item=json.loads(line);clients[str(item['roomId'])]=item['messages']
    events=defaultdict(list)
    for line in (RESULTS/'server-events.jsonl').read_text().splitlines():
        item=json.loads(line);events[str(item['event']['roomId'])].append(item)
    sessions=[]
    for node in ['A','B']:
        sessions += [json.loads(line) for line in (RESULTS/f'sessions-{node}.jsonl').read_text().splitlines()]
    for row in rows:
        room=row['roomId'];trace=traces[room];messages=clients[room]
        connections=[m for m in messages if m['kind']=='connected']
        assert connections[0]['wsNode']=='A' and connections[0]['snapshotNode']=='A',room
        assert connections[-1]['wsNode']=='B' and connections[-1]['snapshotNode']=='B',room
        assert connections[0]['sessionId']!=connections[-1]['sessionId'],room
        assert connections[0]['authenticatedUser']==connections[-1]['authenticatedUser'],room
        before=connections[0]['snapshot'];after=connections[-1]['snapshot']
        assert before['performance']['startedAt']==after['performance']['startedAt'],room
        elapsed=(seconds(after['serverNow'])-seconds(before['serverNow']))*1000
        progressed=after['playback']['playbackPositionMs']-before['playback']['playbackPositionMs']
        assert abs(progressed-elapsed)<=2,(room,progressed,elapsed)
        for connection in [connections[0],connections[-1]]:
            history=[s for s in sessions if s['sessionId']==connection['sessionId'] and s['instance']==connection['wsNode']]
            assert any(s['kind']=='CONNECTED' and s['user']==connection['authenticatedUser'] for s in history),room
            for destination in [f'/topic/rooms/{room}','/user/queue/lb-probe']:
                assert any(s['kind']=='SUBSCRIBED' and s['destination']==destination for s in history),(room,destination)
        killed=next(s for s in trace['states'] if s['stage']=='killed')['process']
        assert killed['ExitCode']==137,room
        assert seconds(killed['FinishedAt']) < seconds(row['activateAt'] if row['case']=='PENDING' else row['endsAt']),room
        counts=Counter(e['event']['eventType'] for e in events[room])
        for event,field in [('CARD_EFFECT_STARTED','serverStarts'),('CARD_EFFECT_ENDED','serverEnds'),('CARD_ACTIVATION_CANCELLED','serverCancels')]:
            assert counts[event]==int(row[field]),(room,field)
        received=Counter(m['event']['eventType'] for m in messages if m['kind']=='event')
        assert received['CARD_EFFECT_ENDED']==int(row['clientEnds']),room
        final=next(s['value'] for s in trace['states'] if s['stage']=='final_snapshot')
        local=next(s['value'] for s in trace['states'] if s['stage']=='final_client')
        assert final['snapshot']['myCard']['status']=='USED',room
        if row['recoveryEnabled']=='True':
            assert final['snapshot'].get('activeCard') is None and local.get('activeCard') is None and local.get('pendingCard') is None,room
            assert counts['CARD_EFFECT_ENDED']==1 and int(row['duplicateServerEvents'])==0,room
            assert next(e for e in events[room] if e['event']['eventType']=='CARD_EFFECT_ENDED')['instance']=='B',room
            if row['case']=='PENDING':
                assert next(e for e in events[room] if e['event']['eventType']=='CARD_EFFECT_STARTED')['instance']=='B',room
            if row['case']=='LATE_RECONNECT':
                assert row['snapshotState']=='NONE' and received['CARD_EFFECT_ENDED']==0,room
        else:
            assert final['snapshot']['activeCard']['roomCardStatus']=='ACTIVE' and local['activeCard'] is not None,room
    assert len(rows)==30
    for case in ['ACTIVE','PENDING','LATE_RECONNECT']:
        sample=[r for r in rows if r['case']==case]
        assert len(sample)==10 and all(r['outcome']=='PASS' for r in sample),(case,len(sample))
    root=HERE.parents[3]
    metadata=json.loads((RESULTS/'build-metadata.json').read_text())
    assert metadata['compileExitCode']==0
    for file,digest in metadata.get('sourcesNormalizedLf',metadata['sources']).items():
        content=(root/file).read_bytes()
        if 'sourcesNormalizedLf' in metadata:content=content.replace(b'\r\n',b'\n')
        assert hashlib.sha256(content).hexdigest()==digest,file
    cross=[json.loads(line) for line in (RESULTS/'cross-node-results.jsonl').read_text().splitlines()]
    assert len(cross)==11 and all(r['outcome']=='PASS' for r in cross)
    live=[r for r in cross if r['case']=='LIVE_ROOM_AND_PRIVATE']
    assert len(live)==1 and live[0]['roomMessages']==live[0]['privateMessages']==100
    cross_traces=[json.loads(line) for line in (RESULTS/'cross-node-traces.jsonl').read_text().splitlines()]
    assert len(cross_traces)==11
    for trace in cross_traces:
        messages=trace['messages']
        assert any(m['kind']=='identity' and m['value']['instance']=='A' for m in messages)
        assert not any(m['kind']=='transportError' for m in messages)
        room_events=[m['event'] for m in messages if m['kind']=='event']
        if any(e['eventType']=='ROOM_RELAY_PROBE' for e in room_events):
            for kind in ['ROOM_RELAY_PROBE','PRIVATE_RELAY_PROBE']:
                subset=[e for e in room_events if e['eventType']==kind]
                assert [e['payload']['sequence'] for e in subset]==list(range(100))
                assert all(e['payload']['sourceNode']=='B' for e in subset)
        else:
            assert Counter(e['eventType'] for e in room_events)=={'CARD_EFFECT_STARTED':1,'CARD_EFFECT_ENDED':1}
            journal=[e for e in events[str(trace['roomId'])] if e['event']['eventType'] in ['CARD_EFFECT_STARTED','CARD_EFFECT_ENDED']]
            assert len(journal)==2 and all(e['instance']=='B' for e in journal)
    cold=[json.loads(line) for line in (RESULTS/'cold-results.jsonl').read_text().splitlines()]
    assert len(cold)==2 and all(r['outcome']=='PASS' for r in cold)
    assert {r['case']:r['assignment'] for r in cold}=={'COLD_PENDING':'ASSIGNED','COLD_ACTIVE':'USED'}
    cold_traces=[json.loads(line) for line in (RESULTS/'cold-traces.jsonl').read_text().splitlines()]
    assert len(cold_traces)==2
    for trace in cold_traces:
        assert trace['aStillDead'] and trace['final']['instance']=='B'
        assert trace['final']['snapshot'].get('activeCard') is None
        assert all(state['ExitCode']==137 for state in trace['killed'].values())
        assert all(seconds(state['FinishedAt'])<seconds(trace['deadline']) for state in trace['killed'].values())
        types=Counter(e['event']['eventType'] for e in trace['events'])
        if trace['case']=='PENDING':
            assert types['CARD_ACTIVATION_CANCELLED']==1 and types['CARD_EFFECT_STARTED']==types['CARD_EFFECT_ENDED']==0
            assert trace['final']['snapshot']['myCard']['status']=='ASSIGNED'
            cancellation=next(e for e in trace['events'] if e['event']['eventType']=='CARD_ACTIVATION_CANCELLED')
            assert cancellation['instance']=='B' and cancellation['event']['payload']['cancelReason']=='SYSTEM_CANCELLED'
        else:
            assert types['CARD_EFFECT_STARTED']==types['CARD_EFFECT_ENDED']==1 and types['CARD_ACTIVATION_CANCELLED']==0
            assert trace['final']['snapshot']['myCard']['status']=='USED'
    checks=(RESULTS/'redis-tests.txt').read_text()
    assert re.search(r'26 tests successful',checks) and re.search(r'0 tests failed',checks)
    leaks=[]
    for path in RESULTS.iterdir():
        if path.is_file() and re.search(r'eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+',path.read_text(errors='replace')):leaks.append(path.name)
    assert not leaks,leaks
    result={'passed':True,'matrixRowsChecked':len(rows),'failoverPasses':30,'crossNodeRoomMessages':100,
        'crossNodePrivateMessages':100,'crossNodeCardPasses':10,'coldRestartPasses':2,'junitPasses':26,
        'actualAuthenticatedSessionsVerified':True,'actualUserAndRoomSubscriptionsVerified':True,
        'jwtArtifactMatches':leaks,'currentCompiledSourcesVerified':len(metadata['sources'])}
    (RESULTS/'audit.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
