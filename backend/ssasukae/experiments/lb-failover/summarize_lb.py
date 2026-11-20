"""Summarize only recorded LB failover trials; keep expected control failures explicit."""
from collections import defaultdict
import csv
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path

HERE=Path(__file__).resolve().parent
RESULTS=HERE/'results'
def percentile(values,p):
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)] if values else None

def main():
    rows=list(csv.DictReader((RESULTS/'fault-matrix.csv').open()))
    grouped=defaultdict(list)
    events=defaultdict(list)
    for line in (RESULTS/'server-events.jsonl').read_text().splitlines():
        event=json.loads(line);events[str(event['event']['roomId'])].append(event)
    for row in rows:grouped[(row['phase'],row['case'])].append(row)
    summary=[]
    for (phase,case),sample in grouped.items():
        reconnect=[int(r['reconnectMs']) for r in sample if r['reconnectMs']]
        health=[int(r['healthDownMs']) for r in sample if r['healthDownMs']]
        ends=[int(r['endDelayMs']) for r in sample if r['endDelayMs']]
        starts=[]
        for row in sample:
            for event in events[row['roomId']]:
                if event['event']['eventType']=='CARD_EFFECT_STARTED':
                    starts.append(round((datetime.fromisoformat(event['event']['payload']['startedAt'])-
                        datetime.fromisoformat(row['activateAt'])).total_seconds()*1000))
        summary.append({'phase':phase,'case':case,'trials':len(sample),
            'passed':sum(r['outcome']=='PASS' for r in sample),
            'expectedStuck':sum(r['outcome']=='EXPECTED_STUCK_WITH_LB_ONLY' for r in sample),
            'reconnectedToB':sum(r['reconnectedNode']=='B' for r in sample),
            'snapshotAndSubscriptionRestored':sum(r['snapshotAndSubscriptionRestored']=='True' for r in sample),
            'duplicates':sum(int(r['duplicateServerEvents']) for r in sample),
            'serverEnds':sum(int(r['serverEnds']) for r in sample),
            'serverStarts':sum(int(r['serverStarts']) for r in sample),
            'clientStarts':sum(int(r['clientStarts']) for r in sample),
            'clientEnds':sum(int(r['clientEnds']) for r in sample),
            'reconnectP50Ms':percentile(reconnect,.5),'reconnectP95Ms':percentile(reconnect,.95),
            'healthDownP95Ms':percentile(health,.95),'endDelayP95Ms':percentile(ends,.95),
            'startDelayP95Ms':percentile(starts,.95),
            'maxPlaybackPositionErrorMs':max(int(r['playbackPositionErrorMs']) for r in sample)})
    (RESULTS/'summary.json').write_text(json.dumps(summary,indent=2))
    control=[r for r in rows if r['phase']=='lb-only']
    recovered=[r for r in rows if r['phase']=='recovery']
    prefault_misses=[]
    for row in recovered:
        if row['case']=='ACTIVE' and row['clientStarts']=='0':
            start=next(e for e in events[row['roomId']] if e['event']['eventType']=='CARD_EFFECT_STARTED')
            if start['instance']=='B' and datetime.fromisoformat(start['observedAt'])<datetime.fromisoformat(row['killRequestedAt']):
                prefault_misses.append({'roomId':row['roomId'],'initialClientNode':'A','startNode':'B',
                    'startObservedAt':start['observedAt'],'killRequestedAt':row['killRequestedAt'],
                    'snapshotRecoveredState':row['snapshotState']})
    (RESULTS/'delivery-observations.json').write_text(json.dumps({'prefaultCrossNodeStartMisses':prefault_misses},indent=2))
    lines=['# Docker 로드밸런서를 통한 공연 상태 재연결 실험','',
        f'LB만 적용한 대조군은 {len(control)}회 모두 B 재연결에 성공했지만 카드 종료 예약 유실로 ACTIVE가 남았다. 복구 워커를 함께 적용한 반복 실험은 {len(recovered)}회 중 {sum(r["outcome"]=="PASS" for r in recovered)}회에서 B 재연결·인증·구독·스냅샷 복원과 카드 종료를 통과했다.','',
        'HAProxy 뒤에 별도 JVM A/B와 공유 MySQL·Redis를 두었다. A만 SIGKILL하고 A가 죽은 동안 B에서 자동 재인증·재구독·실제 공연 스냅샷 조회와 카드 종료가 가능한지 측정했다. 운영 소스는 변경하지 않았다.','',
        '## 관측 결과','',
        '| 조건 | 시나리오 | 횟수 | B 재연결 | 상태·구독 복원 | 카드 정상 종료 | 의도한 정체 | 중복 | 재연결 p50/p95 ms | 종료 지연 p95 ms |',
        '|---|---|---:|---:|---:|---:|---:|---:|---|---|']
    for s in summary:
        lines.append(f'| {s["phase"]} | {s["case"]} | {s["trials"]} | {s["reconnectedToB"]} | {s["snapshotAndSubscriptionRestored"]} | {s["passed"]} | {s["expectedStuck"]} | {s["duplicates"]} | {s["reconnectP50Ms"]}/{s["reconnectP95Ms"]} | {s["endDelayP95Ms"]} |')
    lines+=['','## 이벤트 누락과 스냅샷 복원을 구분한 결과','',
        '| 조건 | 시나리오 | 서버 시작/종료 시도 | 클라이언트 시작/종료 수신 |',
        '|---|---|---|---|']
    for s in summary:
        lines.append(f'| {s["phase"]} | {s["case"]} | {s["serverStarts"]}/{s["serverEnds"]} | {s["clientStarts"]}/{s["clientEnds"]} |')
    lines += ['',f'ACTIVE 반복 중 {len(prefault_misses)}회는 A 장애 직전에 B가 시작 상태 전이를 선점하고 시작 이벤트를 발행했지만 A에 연결된 클라이언트는 수신하지 못했다. 이후 B 재연결 시 ACTIVE 스냅샷으로 복원됐다. 이 관측은 기존 노드별 simple broker의 실시간 전달 문제가 남아 있음을 보여주며, 재연결 성공이 교차 노드 이벤트 전달 보장을 뜻하지 않는다.']
    lines+=['','## 조건과 판정','',
        '- lb-only: 두 JVM의 복구 워커 비활성화. LB만으로 B 재연결은 되지만 ACTIVE가 endsAt 이후 남는 대조군이다. EXPECTED_STUCK_WITH_LB_ONLY는 복구 성공을 뜻하지 않는다.',
        '- ACTIVE: 효과 시작 후 A를 종료한다. B에서 재연결 당시 원래 endsAt과 남은 시간이 유지되고, 종료 이벤트를 한 번 수신해야 통과한다.',
        '- PENDING: 3초 activateAt 이전에 A가 실제 종료됐는지 Docker FinishedAt으로 확인한다. 이미 실행 중인 B 워커가 예정된 시작/종료를 이어간다. cold restart 실험과 다른 hot failover 조건이다.',
        '- LATE_RECONNECT: 클라이언트 재접속 대기를 의도적으로 8초로 늘려 6초 카드의 종료 이벤트를 놓치게 한다. B의 최신 스냅샷(activeCard=null)으로 남아 있던 로컬 효과 상태를 해제해야 통과한다.',
        '- 일반 재연결 간격은 현 프론트 createStompClient와 같은 3초다. 끊김 감지는 TCP 연결 종료에 의존하며, 무응답 네트워크 blackhole은 재현하지 않았다.',
        '- HAProxy balance first로 A를 우선 선택한다. A/B 모두 장애 전 UP이어야 하며, A는 해당 trial 종료까지 재시작하지 않는다. B는 기존 워커로 계속 동작한다.',
        '- HTTP와 STOMP 모두 HAProxy 주소만 사용한다. 백엔드와 Redis·MySQL 포트는 호스트에 공개하지 않는다. 실제 STOMP identity 응답으로 연결 노드를 확인한다.',
        '- 스냅샷은 실제 RoomService.getRoomSnapshot와 DTO를 사용한다. HTTP wrapper에서 JWT·활성 세션을 검증하고, STOMP는 기존 인증·인가 인터셉터를 사용한다.',
        '- user queue identity와 room topic 왕복 응답으로 재인증·재구독을 확인한다. 공연 ID·참가자 ID 목록·재생 위치·카드 deadline을 비교한다.',
        '- MySQL·Redis는 계속 유지한다. 완료 fixture의 카드·배정·공연 키만 측정 후 UNLINK하며, 이 정리는 복구 성공에 포함하지 않는다.','',
        '## 해석상의 한계','',
        '- 브라우저 React/Zustand UI나 실제 오디오·영상은 실행하지 않았다. headless Java STOMP probe가 프론트의 subscribe → snapshot hydrate 순서와 카드 상태 모델을 따른다.',
        '- RoomService의 스냅샷 읽기 경로만 노출했다. S3 URL 발급은 fixture URL로 대체하며 미디어 세션 관련 미사용 의존성은 로드하지 않았다.',
        '- HTTP는 실험 wrapper에서 인증 후 실제 RoomService를 호출한다. 운영 HTTP JWT 필터 체인, 실제 방 입장/퇴장 presence 흐름과 미디어 재접속은 검증하지 않았다.',
        '- 현재 API에 없는 상태 version/event sequence를 새로 추가하지 않았다. snapshot/event가 임의 순서로 겹치는 모든 경계를 증명하는 테스트가 아니다.',
        '- A가 살아 있고 다른 노드가 이벤트를 발행할 때의 simple broker 간 전달 누락 문제는 이전 실험에서 확인됐으며, 이 실험으로 해결됐다고 주장하지 않는다.',
        '- AWS ALB의 실제 헬스체크·타임아웃·sticky routing·가용영역 장애는 검증하지 않았다. Docker 단일 호스트의 앱 JVM 장애와 로컬 프록시 재연결 실험이다.',
        '- 재연결 지연은 kill 요청 → 인증·구독 왕복 및 스냅샷 적용 완료 시각이다. HAProxy DOWN 지연도 kill 요청 기준으로, Docker CLI 호출 비용과 관측 간격이 포함된다.',
        '- late reconnect에서 종료 이벤트 미수신은 의도된 조건이다. 스냅샷 복원 성공과 실시간 메시지 수신 성공을 구분한다.','',
        '## 파일','',
        '- fault-matrix.csv, summary.json: 각 trial 판정과 지연 집계.',
        '- client-traces.jsonl, state-traces.jsonl: 자동 재연결·스냅샷·로컬 상태·LB health 관측.',
        '- sessions-A/B.jsonl, server-events.jsonl: 실제 인증·구독과 서버 이벤트 발행 시도.',
        '- process-log.jsonl, *-services.txt: Docker SIGKILL·재시작과 HAProxy 라우팅 로그.',
        '- verified-configurations.jsonl: 실제 복구 설정과 백엔드 포트 비공개 검증.',
        '- build-metadata.json, runtime-images.json: 소스 및 이미지 출처.','',
        'HAProxy 설정 근거: https://docs.haproxy.org/3.2/configuration.html','']
    (RESULTS/'REPORT.md').write_text('\n'.join(lines))
    print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
