# 로드밸런서 재연결 실험

Docker Compose로 HAProxy + Spring Boot A/B + 실제 MySQL/Redis를 실행한다.
기존 card-scheduler 실험의 복구 후보 이미지를 base로 사용한다. 운영 소스 변경은 없다.

```bash
python3 build.py
python3 run_lb.py --phase lb-only --recovery off --cases ACTIVE --repetitions 5 --keep
python3 run_lb.py --phase recovery-smoke --recovery on --repetitions 1 --keep
python3 run_lb.py --phase recovery --recovery on --repetitions 10
python3 summarize_lb.py
```

WSL의 Windows Docker CLI를 사용한다. 설정은 WSLENV 상속 대신 --env-file로 명시한다.
백엔드·저장소 포트를 노출하지 않으며 클라이언트의 HTTP와 STOMP 경로는 HAProxy로 제한한다.
실험 도중 A만 SIGKILL하고, B가 계속 살아 있는 상태에서 자동 재연결을 관측한다.

일반 클라이언트의 재연결 대기는 실제 frontend/src/shared/api/stomp.ts와 같은 3초다.
late case만 8초 대기를 지정하여 종료 이벤트를 놓친 후 snapshot으로 효과가 해제되는지 확인한다.
브라우저 UI·실제 미디어 세션 재연결은 범위 밖이다.

완료 시 전용 컨테이너와 네트워크를 정리하며 전용 DB 볼륨은 보존한다.
기존 card-scheduler 결과 파일과 이미지 태그는 변경하지 않는다.
