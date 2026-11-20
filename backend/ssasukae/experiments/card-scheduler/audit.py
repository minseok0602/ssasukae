"""Cross-check the completed fault matrix against raw state and event evidence."""
from collections import defaultdict
import csv
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

from build import HERE, ROOT


def main():
    results=HERE/'results'
    rows=list(csv.DictReader((results/'fault-matrix.csv').open()))
    snapshots={}
    for line in (results/'redis_state_before_after.jsonl').read_text().splitlines():
        item=json.loads(line)
        snapshots[(item['phase'],str(item['snapshots'][0]['roomId']))]=item['snapshots']
    events=defaultdict(list)
    for line in (results/'server-events.jsonl').read_text().splitlines():
        event=json.loads(line); events[str(event['event']['roomId'])].append(event)
    checked=0
    for row in rows:
        key=(row['phase'],row['roomId'])
        assert key in snapshots,key
        final=snapshots[key][-1]['card']
        assert (final or {}).get('status','NONE')==row['finalState'],key
        counts=defaultdict(int)
        # Each fixture owns a unique room and activates once. Compare the complete room journal:
        # deletion can be observed a fraction of a millisecond before its terminal event is written,
        # and run.py records judgedAt immediately before reading the event counters.
        for event in events[row['roomId']]:
            counts[event['event']['eventType']]+=1
        assert counts['CARD_EFFECT_STARTED']==int(row['serverStartAttempts']),key
        assert counts['CARD_EFFECT_ENDED']==int(row['serverEndAttempts']),key
        for snap in snapshots[key]:
            if snap.get('processState'): assert snap['processState']['ExitCode']==137,key
            if snap['stage']=='after_kill' and snap['card']: assert snap['ttlMs']>0,key
        checked+=1
    for phase,outcome in [('baseline','STUCK'),('after-cancel','CONVERGED')]:
        for case in ['S-02','S-03','S-04']:
            sample=[r for r in rows if r['phase']==phase and r['testId']==case]
            assert len(sample)==20,(phase,case,len(sample))
            assert all(r['outcome']==outcome and r['timingValid']=='True' for r in sample),(phase,case)
    for case in ['S-05P','S-05A','S-07-before','S-07-after','S-08','S-06']:
        sample=[r for r in rows if r['phase'] in ['after-extended','after-race'] and r['testId']==case]
        assert len(sample)==5,(case,len(sample))
        assert all(r['outcome']=='CONVERGED' and r['timingValid']=='True' for r in sample),case
        if case=='S-06':
            for row in sample:
                log=(results/f"after-race-S-06-{row['iteration']}-app.txt").read_text()
                instances=set(re.findall(r'RECOVERY_LOCK_ATTEMPT instance=(\w+) room='+row['roomId']+r'\b',log))
                assert instances=={'app','app2'},(row['roomId'],instances)
    assert not [r for r in rows if r['phase']=='after-bounded-start']
    metadata=json.loads((results/'build-metadata.json').read_text())
    production=0
    for relative,expected in metadata['sourceSha256'].items():
        if '/src/main/java/' in relative:
            assert hashlib.sha256((ROOT/relative).read_bytes()).hexdigest()==expected,relative
            production+=1
    jwt_files=[]
    for path in results.iterdir():
        if path.is_file() and re.search(r'eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+',path.read_text(errors='replace')):
            jwt_files.append(path.name)
    assert not jwt_files,jwt_files
    report={'passed':True,'matrixRowsCrossChecked':checked,'productionSourcesHashMatched':production,
            'coreBaseline':'60/60 stuck','coreCandidate':'60/60 converged',
            'extensionCases':'6 cases x5 valid converged','policy':'user-selected cancellation',
            'rawStateEventAgreement':True,'jwtArtifacts':jwt_files}
    (results/'audit.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
