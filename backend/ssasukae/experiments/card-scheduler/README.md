# 카드 JVM 장애 복구 실험

사용자가 제공한 `04_Ssasukae_A_card_scheduler_recovery.md`의 로컬 실험이다.
운영 소스는 수정하지 않는다. 별도 Spring Boot 진입점에서 실제 CardService,
RedisCardStateStore, RedisPerformanceStore, CardWebSocketController, JWT 인증·인가,
MySQL 방 행 잠금과 트랜잭션, 실제 STOMP simple broker를 사용한다.
OpenVidu·AI·공연 복구 워커는 로드하지 않는다. 방/참가자와 카드 배정은 fixture로 준비한다.

## 실행

Docker Desktop, Python 3, 호스트 Java 17 이상, 캐시된 프로젝트 의존성이 필요하다.
WSL에서는 Windows Docker CLI를 사용한다. `CARD_EXPERIMENT_DOCKER`로 CLI 경로를 지정할 수 있다.
현재 Gradle wrapper 다운로드가 제한된 환경이어서, `build.py`는 기존 Gradle 캐시의
artifact별 최신 JAR를 사용해 Java 17 컨테이너에서 오프라인 javac 컴파일한다.
정규 Gradle dependency resolution과 동일하다고 가정하지 않는다.
실제 버전·해시·소스 해시를 `results/build-metadata.json`에 남긴다.
새 소스를 반영할 때는 컴파일 후 이미지도 다시 빌드해야 한다.
실행 설정은 Windows CLI의 WSL 환경변수 상속에 의존하지 않고 `build/compose-experiment.env`를
`--env-file`로 전달한다. 시작 직후 실제 컨테이너 이미지·복구 활성화·정책을 확인한다.

```bash
export CARD_EXPERIMENT_DOCKER='/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe'
python3 build.py
"$CARD_EXPERIMENT_DOCKER" compose -f compose.yaml build app
python3 run.py --phase baseline --repetitions 20 --duration 10 --keep

# 실험 후보 이미지. baseline 실행 중 local 태그를 덮어쓰지 않는다.
"$CARD_EXPERIMENT_DOCKER" build -t ssasukae-card-experiment:candidate .
python3 run.py --phase after-cancel --policy cancel --cases CONTROL S-02 S-03 S-04 --repetitions 20 --duration 10 --keep
python3 run.py --phase after-bounded-start --policy bounded-start --cases S-02 --repetitions 20 --duration 10 --keep

# 보조 및 확장 시나리오. 횟수는 결과 보고서에 실제 실행 값으로 명시한다.
python3 run.py --phase after-supplement --policy cancel --cases S-01 S-01-extended --repetitions 1 --duration 10 --keep
python3 run.py --phase after-extended --policy cancel --cases S-05P S-05A S-07-before S-07-after S-08 --repetitions 5 --duration 10 --keep
python3 run.py --phase after-race --policy cancel --cases S-06 --repetitions 5 --duration 20
python3 summarize.py

java -cp 'build/classes:build/lib/*' experiment.UnitChecks
```

`run.py`는 전용 Compose 프로젝트의 `app`만 SIGKILL한다. Redis는 일반 장애 실험에서
계속 실행한다. S-08만 Redis 접근 실패를 위해 Redis를 일시 종료·재시작하며 AOF 볼륨을 유지한다.
S-06은 두 JVM의 주기 스캔 간격을 1시간으로 두고, 종료 deadline 이후 두 워커를
동시에 HTTP로 호출하여 같은 방 잠금 경합을 만든다. 이 HTTP는 카드 활성화 API가 아니다.
카드 활성화는 항상 인증된 STOMP `/app/rooms/{roomId}/performances/{performanceId}/cards/activate`로 보낸다.

JWT는 probe의 표준입력으로만 전달하며 산출물에 저장하지 않는다. 모든 DB 자격 증명은
전용 로컬 fixture 값이다. HTTP fixture 엔드포인트는 127.0.0.1의 실험 포트에만 노출한다.
`--keep` 생략 시 컨테이너와 네트워크를 내리고, 조사용 전용 DB 볼륨은 보존한다.

## 복구 후보의 범위

`SnapshotRecoveryWorker`는 **실험용 후보**이며 운영 앱의 component scan 밖에 있다.
기본값은 비활성화이고, 명시적인 정책으로만 활성화한다.

- `cancel`: 재시작 시 이미 activation deadline을 지난 PENDING은 SYSTEM_CANCELLED로 정리한다.
- `bounded-start`: 공연·참가자·배정을 다시 검증하고 30초 이내 지연만 즉시 시작한다. 초과하면 취소한다.
- 재시작 전에 남은 예약은 스냅샷의 activateAt/endsAt을 100ms 간격으로 확인한다.
- 방 잠금 안에서 최신 카드·공연 상태를 다시 읽어 시작·종료·취소를 결정한다.
- 별도 Sorted Set 대신 기존 스냅샷을 SCAN하여 deadline 인덱스와 상태의 이중 쓰기 간극을 피한다.
- 한 방/Redis 접근 실패는 기록하고 다음 스캔에서 재시도한다.

사용자가 실험 도중 `cancel` 정책을 선택했다(`results/selected-policy.json`).
이에 따라 `after-bounded-start` 비교 단계는 생략한다. `bounded-start` 코드는 미채택 대안이다.
운영 도입 전에는 스캔 규모, 상태/배정 저장 사이의 부분 실패, 이벤트 outbox,
기존 예약 callback의 카드 인스턴스 식별 규약을 별도로 검토해야 한다.

## 해석 규칙

- `fault-matrix.csv`: 실제 실행한 카드만 기록한다. timingValid=false는 해당 시나리오 성공률에서 제외한다.
- S-01은 실제 3초 countdown 내 cold restart가 완료돼야 유효하다.
  S-01-extended는 **별도 fixture**로 SIGKILL 후 deadline을 12초 앞으로 연장한다.
  미래 PENDING 복구 경로의 보조 검증이며, 원래 S-01을 통과한 것으로 계산하지 않는다.
- 정체 판정은 재시작 준비+2초 및 예상 종료+1초가 지난 뒤의 상태다.
  30분 TTL까지 전부 기다렸다는 뜻이나 수학적 의미의 영구 상태를 뜻하지 않는다.
- `server-events.jsonl`은 브로커 호출 직전의 **발행 시도** 기록이다.
  서버 이벤트 시도와 `client-events.jsonl`의 STOMP 수신을 구분한다.
  재연결 전 구독 공백의 메시지는 수신 보장이 없으며, 서버 상태 멱등성이 전달 보장을 뜻하지 않는다.
- 복구 지연은 PENDING의 첫 시작/취소, ACTIVE의 종료 시각에서
  max(해당 전이 deadline, 재시작 요청 시각)을 뺀 값이다. 공연 종료에 따른 정리는
  재시작 요청 시각 기준이다. cold start를 포함한다. crash 전에 이미 정상 종료한 경계 실험은 미측정이다.
- 정상 종료 시 카드 상태가 삭제되는지, 카드 배정이 ASSIGNED/USED 중 무엇으로 남는지는
  `redis_state_before_after.json`에서 함께 확인한다. TTL은 장애 전·직후·재시작 후에 기록한다.
- 프로세스 종료·재시작 로그, Redis 버전, 컴파일 및 JUnit 결과를 함께 보관한다.
  프론트의 실제 오디오/영상 효과 적용, 클라우드 장애, 브로커의 정확히 한 번 전달은 검증 범위 밖이다.
