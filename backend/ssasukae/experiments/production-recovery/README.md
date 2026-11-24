# 운영 카드 복구 및 서버 간 이벤트 전달 검증

이 디렉터리는 **현재 `src/main`의 구현**을 검증한다. 앞선 `card-scheduler`와 `lb-failover`의 결과는 변경하지 않는다. 이전 실험의 JVM 라이브러리, 데이터 생성기, 프로토콜 클라이언트는 재사용하지만 `SnapshotRecoveryWorker` 시제품을 Spring Bean으로 등록하지 않는다.

변경한 동작:

- 각 JVM이 Redis의 카드 스냅샷을 주기적으로 발견하고 예약을 복원한다. MySQL 방 잠금과 Redis 비교 후 변경으로 여러 JVM의 경합을 조정한다.
- 미래 PENDING은 원래 `activateAt`에 실행한다. 이미 지난 예약을 새로 발견했을 때는 늦게 실행하지 않는다. 정상 등록된 타이머에는 500ms 오차를 허용하며, 이후 SYSTEM_CANCELLED로 정리한다. 유효한 공연의 미사용 개인 배정은 ASSIGNED로 유지한다.
- ACTIVE는 원래 `endsAt`을 유지한다. 시간이 지났으면 종료하며, 새 서버에서 지속 시간을 다시 시작하지 않는다.
- 카드 배정 USED 변경과 PENDING→ACTIVE 변경은 한 Redis Lua 실행으로 처리한다. 과거 실행의 콜백은 이후 카드 실행을 변경할 수 없다.
- 공통 WebSocket 발행기는 Redis Stream에 기록한다. 각 JVM은 독립적인 커서로 모든 레코드를 읽고 자신의 방/개인 구독자에게 전달한다. 하나의 consumer group으로 서버끼리 이벤트를 분배하지 않는다.

## 실행

기본 포트는 `18190`(HAProxy), `18191`(통계), `18192`(B로만 보내는 실험 제어 요청)다. 백엔드 자체 포트는 호스트에 공개하지 않는다. MySQL/Redis 볼륨과 Compose 프로젝트는 앞선 실험과 분리된다. 인증은 실제 JWT 및 활성 세션을 사용하며 토큰은 결과 파일에 기록하지 않는다. HTTP fixture 경로와 미디어 대역은 실험 전용이다.

Java 17과 Gradle 의존성을 가진 기존 로컬 이미지 `ssasukae-lb-experiment:local`이 필요하다. 이번 환경에서는 Gradle 배포 파일 다운로드 제약 때문에 이 이미지의 캐시 라이브러리로 **현재 운영 Java 소스 전체를 다시 컴파일**했다. Gradle의 전체 빌드/전체 테스트를 실행한 결과로 해석하지 않는다. `results/build-metadata.json`은 컴파일 입력 원본의 SHA-256을 보존하고, Windows/Linux 체크아웃 간 대조를 위한 LF 정규화 해시도 함께 기록한다. `audit.py`는 줄바꿈만 정규화해 현재 소스를 대조한다.

저장소 루트에서 실행한다. Windows Docker를 WSL에서 사용하는 기본 경로이며 다른 환경에서는 `CARD_EXPERIMENT_DOCKER`로 Docker CLI를 지정한다.

```sh
python3 backend/ssasukae/experiments/production-recovery/build.py
python3 backend/ssasukae/experiments/production-recovery/run_verification.py start
python3 backend/ssasukae/experiments/production-recovery/run_checks.py
python3 backend/ssasukae/experiments/production-recovery/run_verification.py cross
python3 backend/ssasukae/experiments/production-recovery/run_verification.py failover 10
python3 backend/ssasukae/experiments/production-recovery/run_verification.py cold
python3 backend/ssasukae/experiments/production-recovery/summarize.py
python3 backend/ssasukae/experiments/production-recovery/run_verification.py down
```

`run_checks.py`는 워커가 없는 별도의 Redis를 생성하고 마지막에 삭제한다. 운영 워커가 돌고 있는 Redis를 저장소 테스트와 공유하면 존재하지 않는 방 ID의 테스트 스냅샷을 정상적으로 정리해 테스트와 간섭한다. 최초 공유 Redis 재검증에서 발생한 실패는 `results/shared-redis-*`에 남겼다. 이를 운영 결함 수정으로 집계하지 않는다.

결과 파일은 누적된다. `fault-matrix.csv`만 failover 실행 때 다시 생성한다. 새 측정 집합을 만들 때는 기존 결과 디렉터리를 별도로 보관한다. 실험 중인 동일 Compose 프로젝트에 여러 제어 스크립트를 동시에 실행하지 않는다.

## 검증 범위

`cross`는 A의 복구 워커만 끈 상태에서 A에 실제 STOMP 세션을 유지한다. B가 발행한 방 이벤트 100개와 개인 이벤트 100개를 확인하고, B 워커가 실제 카드 시작과 종료를 처리한 사례를 10회 확인한다. 연결 재시도나 스냅샷 재조회로 live 이벤트 누락을 가리지 않는다.

`failover`는 두 서버의 운영 워커를 켜고 A만 SIGKILL한다. B는 실험 시작 전부터 살아 있다. 활성 카드, 시작 전 카드, 종료 후 늦은 접속을 각 10회 검증한다. 실제 구독/인증 복구, 원래 종료 시각, 재생 위치, 서버 전이 횟수와 클라이언트 최종 상태를 확인한다. 일반 클라이언트 재시도 간격은 기존 프론트와 같은 3초이며, 늦은 접속은 의도적으로 8초다.

`cold`는 두 JVM을 동시에 SIGKILL하고 예정 시각 이후 B만 다시 시작한다. Redis를 유지한 상태에서 지난 PENDING의 취소 및 ASSIGNED 유지, 만료 ACTIVE의 종료 및 USED 유지를 확인한다.

## 운영 설정과 남은 보장 범위

두 기능은 기본 활성화된다. `CARD_RECOVERY_ENABLED=false`와 `WEBSOCKET_RELAY_ENABLED=false`는 비교 실험/비활성화 용도다. 모든 애플리케이션 서버는 동일 Redis와 동일 `WEBSOCKET_EVENT_STREAM` 키를 사용해야 한다. 서로 다른 서비스/환경은 별도 Redis 또는 별도 키를 사용한다.

카드 조회 간격은 `card.recovery.scan-delay=250ms`, 예약 오차는 `card.recovery.start-execution-grace=500ms`다. SCAN은 현재 카드 키를 발견하므로 별도 예약 인덱스 기록 사이에 죽은 경우도 복구한다. 전체 Redis 키 수와 활성 카드 수가 많아지면 모든 노드의 스캔 및 방 잠금 비용을 측정하고 인덱스/리더 방식 등을 검토해야 한다. 이번 검증은 부하 용량 검증이 아니다. 여러 JVM의 시계는 같은 시간 기준으로 동기화되어 있어야 한다.

이벤트 조회 간격은 `websocket.relay.poll-delay=50ms`, 배치는 100개다. `max-length=100000`은 대략적인 보관 레코드 수이며 시간 보장이 아니다. 살아 있는 JVM의 일시적인 Redis 읽기 연결 장애는 같은 커서에서 재시도한다. 신규 JVM은 시작 시 최신 레코드 이후부터 읽으며 새 클라이언트는 스냅샷으로 현재 상태를 가져온다. 릴레이 초기화 시 Redis를 읽지 못하면 서버 시작이 실패한다.

**모든 장애에서 데이터/이벤트 유실을 막는 설계가 완성된 것은 아니다.** 이 변경은 Redis 상태가 남은 상황의 JVM 장애 복구와 노드 간 전달을 해결한다.

- 상태 변경, MySQL 커밋, Stream 기록은 하나의 트랜잭션이 아니다. 상태 반영 후 이벤트 기록 전에 죽는 구간은 남는다. 영구적인 이벤트 재전송까지 보장하려면 상태와 함께 기록하는 outbox 등 추가 설계가 필요하다.
- 브라우저까지의 정확히 한 번 전달/수신 확인을 제공하지 않는다. 이번 중복 0건은 측정 결과다. 릴레이의 로컬 수락 이후 장애나 장시간 지연에 의한 중복/누락 가능성은 별도다.
- Redis 자체 유실/장애 조치, 카드 TTL 30분·공연 TTL 20분을 넘은 중단, Stream 보관 한도를 넘은 지연은 보장 대상이 아니다. Lua의 다중 키 연산은 현재 단일 Redis 기준이며 Redis Cluster 슬롯 설계는 별도다.
- 실제 브라우저, 오디오·OpenVidu, AWS ALB, 네트워크 분할, 대규모 트래픽 검증은 포함하지 않는다. 운영 배포는 수행하지 않았다.

Redis Pub/Sub의 단절 중 메시지 유실 특성과 Streams의 차이는 [Redis 공식 문서](https://redis.io/docs/latest/develop/pubsub/)를 참고한다.
