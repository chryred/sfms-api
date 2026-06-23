# 배포 (deploy/)

레지스트리 없이 **CD에서 이미지 빌드 → tar 반입 → 서버에서 Podman + systemd(Quadlet) 기동** 하는 흐름의 템플릿.

| 파일 | 용도 |
|---|---|
| `sfms-api.container` | Podman Quadlet 유닛(systemd가 .service로 자동 변환). `Pull=never` |
| `load-and-restart.sh` | 서버에서 tar 적재 → `:current` 태깅 → daemon-reload → restart |

> ⚠️ 전제: `@SpringBootApplication` 진입점이 생겨 이미지가 빌드돼야 함(ROADMAP). 아래는 그 이후 절차.

## 1. CD(빌드) 측 — 이미지를 tar로
```bash
IMAGE=localhost/sfms-api:0.0.1
podman build -t "$IMAGE" .          # 또는 ci/build-image.sh
podman save -o sfms-api-0.0.1.tar "$IMAGE"
# sfms-api-0.0.1.tar 를 서버로 scp/rsync
```

## 2. 서버 측 — 최초 1회 설치
```bash
sudo cp deploy/sfms-api.container /etc/containers/systemd/   # rootless: ~/.config/containers/systemd/
# .container 안의 NLS_LANG / DB URL / 계정을 운영값으로 수정
podman secret create sfms-db-password -                      # DB 비밀번호 입력(stdin)
sudo systemctl daemon-reload
sudo systemctl start sfms-api
systemctl status sfms-api
```

## 3. 서버 측 — 배포/업데이트 (반복)
```bash
./deploy/load-and-restart.sh sfms-api-0.0.2.tar
```

## 롤백
```bash
podman tag localhost/sfms-api:0.0.1 localhost/sfms-api:current
sudo systemctl restart sfms-api
```

## rootless로 운영 시
- 경로: `~/.config/containers/systemd/`, 명령: `systemctl --user ...`
- `loginctl enable-linger <유저>` (로그아웃 상태에서도 부팅 기동)
- `[Install] WantedBy=default.target`

## 확인/디버깅
```bash
journalctl -u sfms-api -f                                    # 로그
/usr/lib/systemd/system-generators/podman-system-generator --dryrun   # 생성될 유닛 미리보기
```
