"""Candidate smoke checks on Redis DB 1; separate from process-fault statistics."""
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import subprocess
import time
from build import DOCKER
from run import Probe, Redis, RESULTS, http, ready, server_events


def main():
    ready(base='http://127.0.0.1:18086')
    redis = Redis(1)
    probe = Probe()
    checks = []
    try:
        fixture = http('fixture?duration=1', 'POST', base='http://127.0.0.1:18086')
        probe.connect(fixture, url='ws://127.0.0.1:18086/ws')
        probe.send('activate', roomId=fixture['roomId'], performanceId=fixture['performanceId'])
        probe.wait('sent')
        time.sleep(5)
        counts = Counter(e['event']['eventType'] for e in server_events(fixture['roomId']))
        assert counts['CARD_EFFECT_STARTED']==1 and counts['CARD_EFFECT_ENDED']==1, counts
        assert redis.command('GET', f"room:{fixture['roomId']}:active-card") is None
        checks.append({'case':'normal_candidate','passed':True,'counts':dict(counts)})
        for mode in ['expired_pending','expired_active','future_pending']:
            fixture = http('fixture?duration=1', 'POST', base='http://127.0.0.1:18086')
            room=fixture['roomId']; perf=fixture['performanceId']; owner=fixture['participantId']
            assignment=json.loads(redis.command('HGET',f'room:{room}:performance:{perf}:cards',owner))
            performance=json.loads(redis.command('GET',f'performance:session:{perf}'))
            now=datetime.now(timezone.utc)
            card={k:assignment[k] for k in ['roomId','performanceId','cardId','cardCode','cardName',
                'description','effectType','targetType','effectValue','durationSeconds']}
            card.update(sourceParticipantId=owner,targetParticipantId=performance['performerParticipantId'],
                status='ACTIVE' if mode=='expired_active' else 'PENDING',previousValue=None,
                approvedAt=(now-timedelta(seconds=10)).isoformat(),
                activateAt=(now+timedelta(seconds=1) if mode=='future_pending' else now-timedelta(seconds=5)).isoformat(),
                startedAt=(now-timedelta(seconds=4)).isoformat() if mode=='expired_active' else None,
                endsAt=(now-timedelta(seconds=3)).isoformat() if mode=='expired_active' else None)
            if mode=='expired_active':
                assignment.update(status='USED',usedAt=(now-timedelta(seconds=4)).isoformat())
                redis.command('HSET',f'room:{room}:performance:{perf}:cards',owner,json.dumps(assignment))
            redis.command('SET',f'room:{room}:active-card',json.dumps(card),'EX',1800)
            time.sleep(3 if mode=='future_pending' else 1)
            assert redis.command('GET',f'room:{room}:active-card') is None, mode
            counts=Counter(e['event']['eventType'] for e in server_events(room))
            expected= {'CARD_ACTIVATION_CANCELLED':1} if mode=='expired_pending' else (
                {'CARD_EFFECT_ENDED':1} if mode=='expired_active' else {'CARD_EFFECT_STARTED':1,'CARD_EFFECT_ENDED':1})
            assert dict(counts)==expected,(mode,counts)
            checks.append({'case':mode,'passed':True,'roomId':room,'counts':dict(counts)})
        (RESULTS/'preflight.json').write_text(json.dumps(checks,indent=2))
        print(json.dumps(checks,indent=2),flush=True)
    finally:
        probe.close()
        logs=subprocess.run([DOCKER,'logs','ssasukae-card-recovery-preflight'],capture_output=True,text=True)
        (RESULTS/'preflight-app.txt').write_text(logs.stdout+logs.stderr)
        subprocess.run([DOCKER,'rm','-f','ssasukae-card-recovery-preflight'],check=True,capture_output=True)


if __name__=='__main__': main()
