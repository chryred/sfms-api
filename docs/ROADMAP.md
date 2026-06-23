# SFMS API — 향후 진행 사항 (ROADMAP)

현재까지: 인코딩 변환 모듈 + 게이트 테스트 골격 완료. 설계는 [`DESIGN.md`](DESIGN.md) 참조.
아래는 남은 작업을 **게이트(차단 조건) → 스캐폴딩 → 기능** 순으로 정리한 것.

---

## 1. 통과해야 할 게이트 (구현 착수 전 차단 조건)

| 게이트 | 내용 | 상태 |
|---|---|---|
| ① VARCHAR2 라운드트립 | `"한글" 저장→조회→assertEquals`. NLS/드라이버 설정 확정 | ⏳ 테스트 DB 필요 (`EncodingRoundTripIT`) |
| ①-B CLOB 라운드트립 | 1순위(String 트릭) → 실패 시 2순위(바이트 스트림) 채택 | ⏳ 테스트 DB 필요 |
| ② 기존 실데이터 검증 | 운영 VARCHAR2/CLOB 표본 왕복 무손실 + EUC-KR/MS949 스킴 확정 | ⏳ read-only 표본 필요 |
| ③ CVE 스캔 | OWASP Dependency-Check, CVSS≥7 = 0건 | ⏳ CI 구성 필요 |

> 변환기 자체 대칭성(게이트 ①의 1단계, DB 불필요)은 `EncodingConverterTest`로 이미 커버됨.

---

## 2. 스캐폴딩 (실행 가능한 앱 만들기)

현재 없는 것들 — 우선순위 순.

1. **`@SpringBootApplication` 진입점** (`com.sfms.api.SfmsApiApplication`)
2. **`application.yml`** — DataSource(URL/계정), `mybatis.*`, 로깅. 비밀정보는 환경변수/시크릿으로.
3. **Gradle Wrapper** — `gradle wrapper`로 `gradlew` 고정(빌드 재현성).
4. **`@RestControllerAdvice`** 표준 에러 응답 포맷, Actuator 헬스체크.
5. (배포) systemd 유닛 또는 컨테이너 이미지 + `-Dfile.encoding=UTF-8`.

---

## 3. #1 도메인 모델 — **레거시 DDL 입수 후 진행** (현재 블로킹)

브라운필드 DB이므로 실제 테이블 정의가 1차 입력이다. 입수하면:

1. `CREATE TABLE` DDL 분석 → 컬럼 타입/길이, 한글 컬럼 식별.
2. 도메인 엔티티/DTO + MyBatis Mapper 작성.
3. **컬럼별 핸들러 매핑**:
   - 한글 `VARCHAR2` → `KoreanStringTypeHandler`
   - 한글 `CLOB` → `KoreanClobTypeHandler` (게이트 ①-B로 1/2순위 확정)
   - 비한글/숫자 컬럼 → 기본 핸들러(변환 없음)
4. `VARCHAR2(n CHAR/BYTE)` 바이트 여유 확인(저장형이 MS949 바이트라 길이 증가).

---

## 4. API 설계 — 도메인/RN 요구 확정 후

- 시설물 관리 핵심 리소스(시설물·점검·이력·자산 등) 식별 → REST 엔드포인트 설계.
- React Native 화면 요구사항 매핑.
- **인증/인가 방식 결정**(JWT/세션/OAuth) — 미정.

---

## 5. 상시 운영 과제

- **Boot 4.0 → 4.1 상향 추적**: Boot 4.0 보안 지원은 ~2026-12-31. MyBatis가 Boot 4.1 지원 릴리스하면 상향(보안 런웨이 연장). 만료 전 필수.
- **CVE 스캔 게이트 상시화**: Dependabot/Renovate 자동 PR.
- 의존성 핀 버전은 출발점일 뿐, **스캔이 "현재 시점 CVE 없음"을 보증**한다.

---

## 핵심 불변 원칙 (잊지 말 것)

- 더블 인코딩은 **DB 영속화 경계(TypeHandler) 한 곳에서만**. Controller/Service/JSON은 정상 유니코드.
- 드라이버/NLS 동작은 **단정하지 말고 게이트로 검증**.
- 한글 정렬/`LIKE`는 깨진 바이트 기준 → **항상 바인드 파라미터, 인라인 리터럴 금지**.
