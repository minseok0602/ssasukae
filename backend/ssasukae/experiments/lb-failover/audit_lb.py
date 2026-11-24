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
    for case in ['ACTIVE','PENDING','LATE_RECONNECT']:
        sample=[r for r in rows if r['phase']=='recovery' and r['case']==case]
        assert len(sample)==10 and all(r['outcome']=='PASS' for r in sample),(case,len(sample))
    baseline=[r for r in rows if r['phase']=='lb-only']
    assert len(baseline)==5 and all(r['outcome']=='EXPECTED_STUCK_WITH_LB_ONLY' for r in baseline)
    metadata=json.loads((RESULTS/'build-metadata.json').read_text())
    for file,digest in metadata['sources'].items():assert hashlib.sha256((HERE/file).read_bytes()).hexdigest()==digest,file
    production=json.loads((RESULTS/'production-source-check.json').read_text())
    assert not production['changedSinceBaseCompilation']
    leaks=[]
    for path in RESULTS.iterdir():
        if path.is_file() and re.search(r'eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+',path.read_text(errors='replace')):leaks.append(path.name)
    assert not leaks,leaks
    result={'passed':True,'matrixRowsChecked':len(rows),'baselineExpectedStuck':5,'recoveryPasses':30,
        'actualAuthenticatedSessionsVerified':True,'actualUserAndRoomSubscriptionsVerified':True,
        'onlyBPublishedRecoveredEnds':True,'jwtArtifactMatches':leaks,
        'productionSourcesUnchanged':production['checked']}
    (RESULTS/'audit.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
