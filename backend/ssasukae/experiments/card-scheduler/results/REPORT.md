# 카드 JVM 장애 복구 실험 결과

핵심 S-02~04에서 기존 코드는 60회 중 60회 정체됐다. 선택한 취소 정책의 복구 후보는 60회 중 60회 수렴했다. 운영 코드는 변경하지 않았다.

다중 JVM 경합은 상태 중복 없이 수렴했지만, 5회 중 2회 다른 노드의 종료 이벤트가 연결 중인 클라이언트에 도달하지 않았다. 실제 3초 countdown의 S-01은 재시작 시간 조건 미충족으로 미검증이다.

- Git HEAD: `8366a6f3c627cbff207c9531b7c105ba9f02ec3e`. 실행 소스는 기존 변경 사항이 있는 작업 트리이며 소스별 SHA-256을 별도 기록했다.
- Redis: `7.4.12`. MySQL 8.4, 별도 Java 17 Spring Boot 컨테이너, 실제 Redis/STOMP/JWT/MySQL 방 행 잠금 사용.
- 운영 소스 변경 없음. 복구 후보는 실험 전용 진입점에만 연결했다. 사용자가 지연 PENDING의 시스템 취소·상태 정리 정책을 선택했다. 대안인 지연 시작 반복 실험은 생략했다.
- 정규 Gradle wrapper 다운로드 대신 캐시된 의존성을 사용한 오프라인 javac 빌드. 정확한 목록은 build-metadata.json 참조.
- 기존 CardServiceTest: unit-tests.txt 참조. 프로세스 실험은 Python이 별도 JVM에 SIGKILL을 주입했다.

## 실제 관측값

| 실험 조건 | 시나리오 | 실행 | 유효 | 수렴 | 판정 시 정체 | 시간 조건 미충족 | 중복 시도 | 복구 p50/p95 ms |
|---|---|---:|---:|---:|---:|---:|---:|---|
| baseline | CONTROL | 1 | 1 | 1 | 0 | 0 | 0 | 미측정 |
| baseline | S-01 | 20 | 0 | 0 | 0 | 20 | 0 | 미측정 |
| baseline | S-02 | 20 | 20 | 0 | 20 | 0 | 0 | 미측정 |
| baseline | S-03 | 20 | 20 | 0 | 20 | 0 | 0 | 미측정 |
| baseline | S-04 | 20 | 20 | 0 | 20 | 0 | 0 | 미측정 |
| baseline-supplement | S-01-extended | 1 | 1 | 0 | 1 | 0 | 0 | 미측정 |
| after-cancel | CONTROL | 1 | 1 | 1 | 0 | 0 | 0 | 미측정 |
| after-cancel | S-02 | 20 | 20 | 20 | 0 | 0 | 0 | 7415/7706 |
| after-cancel | S-03 | 20 | 20 | 20 | 0 | 0 | 0 | 56/109 |
| after-cancel | S-04 | 20 | 20 | 20 | 0 | 0 | 0 | 7356/8472 |
| after-supplement | S-01 | 1 | 0 | 0 | 0 | 1 | 0 | 미측정 |
| after-supplement | S-01-extended | 1 | 1 | 1 | 0 | 0 | 0 | 162/162 |
| after-extended | S-05P | 5 | 5 | 5 | 0 | 0 | 0 | 8539/8759 |
| after-extended | S-05A | 5 | 5 | 5 | 0 | 0 | 0 | 8610/8868 |
| after-extended | S-07-before | 5 | 5 | 5 | 0 | 0 | 0 | 7472/7764 |
| after-extended | S-07-after | 5 | 5 | 5 | 0 | 0 | 0 | 미측정 |
| after-extended | S-08 | 5 | 5 | 5 | 0 | 0 | 0 | 2057/3415 |
| after-race | S-06 | 5 | 5 | 5 | 0 | 0 | 0 | 175/199 |

## 서버 발행 시도와 클라이언트 수신

| 조건 | 시나리오 | 서버 시작/종료/취소 | 클라이언트 시작/종료/취소 |
|---|---|---|---|
| baseline | CONTROL | 1/1/0 | 1/1/0 |
| baseline | S-01 | 0/0/0 | 0/0/0 |
| baseline | S-02 | 0/0/0 | 0/0/0 |
| baseline | S-03 | 20/0/0 | 20/0/0 |
| baseline | S-04 | 20/0/0 | 20/0/0 |
| after-cancel | CONTROL | 1/1/0 | 1/1/0 |
| after-cancel | S-02 | 0/0/20 | 0/0/20 |
| after-cancel | S-03 | 20/20/0 | 20/20/0 |
| after-cancel | S-04 | 20/20/0 | 20/17/0 |

## 결론과 해석

- Baseline S-02~04: 유효 60회 중 판정 시 정체 60회 (100.0%).
- 취소 정책 복구 후보 S-02~04: 유효 60회 중 판정 시 정체 0회 (0.0%).
- PENDING/ACTIVE 스냅샷은 살아 있지만 JVM 메모리의 예약은 재시작 후 실행되지 않는지, 각 상태와 서버 이벤트 시도를 함께 비교했다.
- S-01은 3초 이내 cold restart 조건을 충족하지 못한 실행을 분모에서 제외한다. S-01-extended는 kill 후 12초 deadline으로 바꾼 보조 fixture이며 원래 S-01의 합격 근거가 아니다.
- 확정 정책: 지연 PENDING은 SYSTEM_CANCELLED로 취소하고 ASSIGNED 배정을 유지한다. 선택 시각과 근거는 selected-policy.json에 기록했다. bounded-start는 미채택 대안 코드이며 반복 측정하지 않았다.
- 복구 후보는 snapshot SCAN + 100ms polling을 사용한다. 시작/종료 전이를 기존 CardService와 동일한 MySQL 방 잠금 아래에서 재검증한다.
- S-06은 두 JVM의 주기 스캔을 1시간 간격으로 두고 deadline 이후 워커를 동시 호출했다. 실제 두 인스턴스의 RECOVERY_LOCK_ATTEMPT는 해당 app 로그에서 확인한다.
- 복구 지연은 PENDING의 첫 시작/취소, ACTIVE의 종료 발행 시도 시각 - max(해당 전이 deadline, 재시작 요청 시각)이다. 공연 종료 시 정리는 재시작 요청 시각 기준이다. cold start가 포함되며, crash 전에 이미 정상 종료한 경계 실험은 미측정이다.
- 정체는 정해진 관측 구간의 결과다. Redis의 30분 TTL까지 전부 기다린 실험이 아니며, TTL에 의한 삭제를 정상 종료로 계산하지 않았다.

## 메시지 전달과 검증 한계

- server-events.jsonl은 브로커 호출 직전의 발행 시도이다. 실제 STOMP 수신은 client-events.jsonl로 별도 기록했다. 재시작~재구독 사이의 메시지는 클라이언트가 놓칠 수 있다.
- 코드 확인: RoomService의 activeCard 스냅샷과 프론트 useRoomSocket → hydrateCardsFromRoomSnapshot 경로는 이미 존재한다. 이 실험에서는 프론트 재연결과 오디오/영상 복원을 직접 실행하지 않았다.
- 서버 상태와 이벤트 발행은 하나의 원자적 outbox 트랜잭션이 아니다. 현재 관측한 중복 0건을 모든 crash 경계에서 정확히 한 번 전달된다는 증명으로 해석하지 않는다.
- 프론트 실제 오디오/영상 효과 적용, 재접속 스냅샷의 프론트 적용, 다중 가용영역·네트워크 분리, TTL 이후의 복구는 미검증이다.
- 별도 deadline index를 만들지 않아 index/snapshot 이중 쓰기 누락은 없지만, ASSIGNED→USED 저장과 ACTIVE 저장 사이의 부분 실패는 별도 실험이 필요하다.
- 미래 PENDING을 관측한 후 시작하는 보조 시나리오 외에도, 운영 적용 전에는 장시간 scan 지연·워커 starvation과 기존 callback의 카드 인스턴스 식별을 검토해야 한다.
- 확장 시나리오의 실행 횟수는 표의 실측값이다. 20회 미만인 행은 계획서의 20~30회 반복 기준을 충족했다고 주장하지 않는다.
- 초기 invalid-configuration-smoke는 WSL 환경변수가 Windows Compose에 전달되지 않아 baseline 이미지로 실행된 점검이다. 비교 통계에서 제외하고 원시 로그와 수정 기록을 보존했다. 이후에는 --env-file 및 실제 컨테이너 image/recoveryEnabled/policy 검증을 적용했다.
- 6자리 fixture 초대 코드 충돌로 카드 생성 전 setup이 실패한 로그도 보존했다. 해당 충돌만 제한적으로 새 fixture를 재시도하며 활성화된 카드의 실패를 재시도로 숨기지 않는다.

## 산출물

- fault-matrix.csv: 식별자, kill/restart 시각, deadline, 최초/최종 상태, 유효성, 서버/수신 이벤트 수, 복구 지연.
- redis_state_before_after.json: 방별 카드/배정 상태와 TTL. 신규 실행은 SIGKILL 종료코드 137 및 실제 FinishedAt도 기록한다.
- process-log.jsonl 및 *-app.txt: Docker 명령·종료·재시작·워커 재시도/경합 로그.
- build-metadata.json, runtime-images.json, redis-version.txt, compile.txt, unit-tests.txt: 실행 환경과 검증 근거.
- summary.json: 집계값. baseline-smoke는 초기 동작 점검이며 주요 통계에서 분리했다.

## 다중 노드 이벤트 전달에서 확인된 추가 문제

- S-06 5회 중 2회에서 app2가 종료 이벤트를 발행했지만 app에 연결된 STOMP probe의 종료 이벤트 수신은 0건이었다. 해당 probe는 두 노드가 준비된 뒤 deadline 이전에 재구독했다.
- 서버 카드 상태는 삭제됐고 종료 발행 시도는 한 번이었다. 따라서 상태 멱등성 통과와 클라이언트의 종료 이벤트 수신 성공을 같은 것으로 해석할 수 없다.
- 코드 근거: WebSocketMessageBrokerConfig의 enableSimpleBroker와 WebSocketEventPublisher의 로컬 SimpMessagingTemplate 호출. 관측 결과는 노드 사이에서 이벤트가 공유되지 않는 구성과 일치한다.
- 다중 노드 운영에는 노드 간 브로커/팬아웃 또는 연결 유지 중에도 상태를 다시 동기화하는 방식이 필요하다. 이번 실험에서는 해당 전달 구조를 변경하지 않았다.
