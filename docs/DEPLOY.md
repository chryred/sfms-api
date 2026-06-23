# SFMS API — 배포 가이드 (Linux / CI/CD)

> 권장 구조: **OCI 이미지로 빌드 → 레지스트리 push → Podman+systemd(또는 k8s)로 실행.**
> Docker에 종속되지 않으며(이미지는 OCI 표준), 같은 Dockerfile이 Podman에서도 무수정 동작.

⚠️ **전제**: `@SpringBootApplication` 진입점이 있어야 `bootJar`/이미지 빌드가 가능하다(ROADMAP 스캐폴딩). 현재는 진입점 부재로 이미지 빌드 단계는 비활성 상태.

---

## 1. 왜 컨테이너 이미지인가 (CI/CD)

| 기준 | 컨테이너 이미지 | systemd + fat jar |
|---|---|---|
| 불변 산출물 | ✅ 버전 태그 고정 | ❌ 환경 드리프트 |
| 환경 승격(dev→stg→prod) | ✅ 재빌드 없이 promote | △ |
| CVE 스캔(§9 게이트) | ✅ 이미지 스캔 자연스러움 | △ |
| 롤백 | ✅ 이전 태그 | △ 수동 |

→ **이미지로 빌드**하되 온프렘 런타임은 **Podman+systemd**로 받는 것이 사내망(레거시 Oracle) 환경의 정석.

---

## 2. 빌드 (로컬/CI 공통)

```bash
# OCI 이미지 빌드 — Docker 또는 Podman 무수정 동일
docker build -t sfms-api:0.0.1 .
# 또는
podman build -t sfms-api:0.0.1 .
```

대안: Dockerfile 관리가 싫으면 Spring Boot 빌드팩 `./gradlew bootBuildImage` (단, 빌더 이미지 pull 가능해야 함 — 에어갭 환경은 Dockerfile 권장).

---

## 3. 실행 — 런타임 옵션

### (A) Podman + systemd Quadlet (권장, 온프렘) ⭐
레지스트리 없이 **CD 빌드 → tar 반입 → 서버 기동**까지 템플릿화되어 있다: **[`deploy/`](../deploy/README.md)**
- `deploy/sfms-api.container` — Quadlet 유닛(`Pull=never`)
- `deploy/load-and-restart.sh` — tar 적재 → `:current` 태깅 → daemon-reload → restart

```bash
# 최초 1회
sudo cp deploy/sfms-api.container /etc/containers/systemd/
podman secret create sfms-db-password -
sudo systemctl daemon-reload && sudo systemctl start sfms-api
# 이후 배포
./deploy/load-and-restart.sh sfms-api-0.0.2.tar
```
> Quadlet은 systemd 제너레이터라 `.service`를 직접 쓰지 않는다. `.container`를 경로에 두고 `daemon-reload` 하면 적용. 부팅기동은 `[Install]`(※ `systemctl enable` 아님).

### (B) Docker
위 `podman` → `docker`로 치환. 동일.

### (C) Kubernetes / k3s (확장 시)
Deployment + Service + Secret(DB·NLS) 매니페스트로 배포. 이미지는 위와 동일.

### (D) 컨테이너 미사용 — systemd + fat jar (가장 단순, CI/CD엔 불리)
```ini
# /etc/systemd/system/sfms-api.service
[Service]
Environment=NLS_LANG=<게이트 확정값>
Environment=JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
EnvironmentFile=/etc/sfms-api/env      # DB 접속 등 시크릿 분리
ExecStart=/usr/bin/java -jar /opt/sfms-api/app.jar
User=sfms
Restart=on-failure
```

---

## 4. 런타임 환경변수 (SFMS 필수)

| 변수 | 용도 | 주의 |
|---|---|---|
| `NLS_LANG` | JDBC 드라이버 인코딩 동작 | **게이트 ①에서 확정한 값**과 반드시 일치(단정 금지) |
| `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` | 기본 인코딩 | 변환 코드는 charset 명시라 보조용 |
| `TZ=Asia/Seoul`, `LANG=C.UTF-8` | 시간/로케일 | Dockerfile에 기본 포함 |
| `SPRING_DATASOURCE_*` | DB 접속 | **이미지에 baking 금지** — 런타임 주입 |

> 시크릿(DB 비밀번호 등)은 이미지/깃에 넣지 않고 **레지스트리 시크릿·k8s Secret·systemd EnvironmentFile**로 주입.

---

## 5. 보안 (DESIGN §9 연계)

- 런타임 **비루트**(Dockerfile에서 `appuser` 적용 완료)
- 이미지 **Trivy 스캔**을 CI 게이트로(High/Critical 발견 시 실패) — `.github/workflows/ci.yml`
- 더 강한 격리: distroless(`gcr.io/distroless/java21`) 또는 read-only rootfs 옵션
- base 이미지(`eclipse-temurin:21-*`) 정기 갱신으로 OS 패치 반영

---

## 6. CI/CD — 엔진 독립 구조

**운영 CI는 사내 Bamboo.** CI 엔진에 종속되지 않도록 **로직은 `ci/*.sh` 스크립트가 단일 진실 원천**이고, 각 엔진 설정은 이를 호출만 한다.

```
ci/test.sh        ./gradlew test          (게이트 ①)
ci/scan.sh        trivy fs CVE 스캔        (게이트 ③, High/Critical 차단)
ci/build-image.sh 이미지 빌드+이미지 스캔+push (진입점 생긴 뒤)
```

| 엔진 | 설정 파일 | 역할 |
|---|---|---|
| **Bamboo (운영)** | `bamboo-specs/bamboo.yml` | `ci/*.sh` 호출. 사내 plan-key/에이전트에 맞게 조정 |
| GitHub (테스트) | `.github/workflows/ci.yml` | 동일 `ci/*.sh` 호출 (불필요 시 삭제 가능) |

파이프라인 흐름:
```
push/PR → ① ci/test.sh → ② ci/scan.sh → ③ ci/build-image.sh(이미지 빌드·스캔·push) [진입점 생긴 뒤]
```

> **Bamboo 주의**: 에이전트에 **JDK 21 / trivy / docker(또는 podman)** capability가 있어야 한다.
> Bamboo Specs(YAML) 문법이 까다로우면 **UI에서 플랜을 만들고 task로 `ci/*.sh`만 호출**해도 결과는 동일하다.
> 시크릿(DB·레지스트리 자격증명)은 **Bamboo 변수/배포 프로젝트의 시크릿**으로 주입, 스크립트·깃에 넣지 않는다.
