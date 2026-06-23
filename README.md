# SFMS API 서버

시설물 관리 시스템(Facility Management System)의 백엔드 API 서버.
React Native 클라이언트에서 호출하는 Java 기반 REST API이며, **Oracle 19c(US7ASCII)에 한글을 깨짐 없이 저장하기 위한 MS949 ↔ ISO-8859-1 더블 인코딩**을 핵심으로 한다.

> 설계 전체는 [`docs/DESIGN.md`](docs/DESIGN.md), 향후 작업은 [`docs/ROADMAP.md`](docs/ROADMAP.md) 참고.

---

## 현재 구현 상태 (정직한 스냅샷)

지금 저장소에 있는 것은 **인코딩 변환 모듈 + 게이트 테스트뿐**이다. 실행 가능한 웹 앱(진입점·설정·도메인)은 아직 스캐폴딩 전이다.

```
src/main/java/com/sfms/api/encoding/
  EncodingConverter.java        # toDb/fromDb (Charset 명시)
  KoreanStringTypeHandler.java  # VARCHAR2 한글 컬럼용 MyBatis TypeHandler
  KoreanClobTypeHandler.java    # CLOB 한글 컬럼용 (1순위 후보 + 2순위 골격 주석)
src/test/java/com/sfms/api/encoding/
  EncodingConverterTest.java    # DB 없이 도는 변환기 대칭성 테스트 (게이트 ① 1단계)
  EncodingRoundTripIT.java      # 실 Oracle VARCHAR2/CLOB 왕복 (@Disabled, 접속정보 입력 후 활성화)
```

아직 **없는 것**(→ `docs/ROADMAP.md`): `@SpringBootApplication` 진입점, `application.yml`, 도메인/Mapper, Gradle Wrapper.

---

## 기술 스택 / 버전 (보안 버전 스펙 — DESIGN §8)

| 영역 | 버전 | 비고 |
|---|---|---|
| JDK | **Temurin 21 LTS** | 장기 보안 패치 + ojdbc11 테스트 범위 |
| Spring Boot | **4.0.7** | 경로 B (mybatis 공식 지원 조합). 보안 지원 ~2026-12 |
| MyBatis | **mybatis-spring-boot-starter 4.0.1** | Boot 4.0 공식 지원 |
| Oracle JDBC | **ojdbc11 23.26.2.0.0** | Oracle 19c 지원 |
| DB | **Oracle 19c (US7ASCII)** | 고정 제약 |

> Jackson/Logback/Tomcat 등은 버전을 명시하지 않고 **Spring Boot BOM에 위임**한다(보안 패치 일괄 적용).

---

## 사전 요구사항

1. **JDK 21 (Temurin 권장)** 설치 — `java -version`으로 21.x 확인.
   - ⚠️ 현재 빌드 머신에 JRE가 없으면 먼저 설치해야 한다(예: `brew install --cask temurin@21` 또는 SDKMAN `sdk install java 21-tem`).
2. **Gradle** — 아직 Wrapper(`gradlew`)가 없으므로, 전역 Gradle(8.x+)을 사용하거나 최초 1회 `gradle wrapper`로 Wrapper를 생성한다.
3. 테스트 DB 접속 정보(통합 게이트 실행 시) — **운영 DB 직접 연결 금지**.

---

## 빌드 & 테스트

> ⚠️ 진입점(main 클래스)이 아직 없어 `bootJar`/`bootRun`은 동작하지 않는다. 현재 검증 수단은 **`test`** 다.

```bash
# 단위 테스트 (DB 불필요) — 변환기 대칭성 게이트
gradle test

# 또는 Wrapper 생성 후
gradle wrapper && ./gradlew test
```

- `EncodingConverterTest` — DB 없이 즉시 통과. `toDb/fromDb` 왕복 무손실을 검증.
- `EncodingRoundTripIT` — `@Disabled` 상태. 실제 Oracle 왕복 게이트(아래 참조).

---

## 환경설정 (인코딩 — 가장 중요)

한글 깨짐 방지는 **설정 + 실측 검증**이 함께 가야 한다. 기능 개발 전 아래 게이트를 통과시킬 것.

### JVM 옵션
```
-Dfile.encoding=UTF-8
```
단, 코드의 변환 로직은 절대 기본 Charset에 의존하지 않고 항상 `MS949`/`ISO-8859-1`을 명시한다.

### 확정해야 할 설정 표면 (게이트에서 핀 고정)
- `NLS_LANG` (클라이언트 환경변수)
- `ojdbc11` 버전 / JDBC connection properties
- 운영 로케일

### 통합 게이트 실행 절차 (DESIGN §4 / §4.5)
1. `src/test/.../EncodingRoundTripIT.java`의 `JDBC_URL`/`USER`/`PASSWORD`를 **테스트 DB**로 채운다.
2. `@Disabled`를 제거하고 `gradle test`로 VARCHAR2 / CLOB 왕복이 무손실인지 확인한다.
3. **CLOB은 별도 게이트(①-B)**: 1순위(String 트릭)가 `?` 손실을 내면 `KoreanClobTypeHandler`를 2순위(바이트 스트림)로 교체.
4. 통과 후에만 CI 게이트에 편입한다.

---

## 보안 (CVE 정책 — DESIGN §9)

"CVE 0건"은 버전 고정만으로 보장되지 않는다(매일 신규 공개). CI에 **차단 게이트**를 둔다.
- OWASP Dependency-Check(또는 Trivy/Grype): CVSS ≥ 7.0 발견 시 빌드 실패
- Dependabot/Renovate: 패치 자동 PR
- (선택) SBOM(CycloneDX) 생성

---

## 다른 PC에서 이어받기 (clone → continue)

```bash
git clone <레포 URL>
cd sfms-api

# Gradle Wrapper가 아직 없으면 최초 1회 생성 (Gradle 8.x+ 설치된 PC에서)
gradle wrapper

# 이후엔 Wrapper로 통일 — 어느 PC든 동일 Gradle 버전 보장
./gradlew test
```

이어받은 뒤 진행 순서:
1. `docs/ROADMAP.md`의 게이트 현황·다음 작업 확인
2. 막힌 작업: **레거시 DDL 입수 → 도메인 모델(#1)**, 테스트 DB 확보 → 인코딩 게이트(①/①-B/②)
3. 민감정보(DB 접속·NLS)는 `.gitignore`에 분리된 `application-local.*` / `.env`로만 다루고 **커밋 금지**

> ⚠️ **Gradle Wrapper는 아직 커밋되어 있지 않다.** 위 `gradle wrapper`를 한 번 실행해 `gradlew`·`gradle/wrapper/`를 만들고 커밋하면, 그 다음부터 모든 PC에서 빌드가 완전히 재현된다. (이 환경엔 Gradle/JRE가 없어 미리 생성하지 못함.)

---

## 문서

- [`docs/DESIGN.md`](docs/DESIGN.md) — 아키텍처·인코딩 해킹·보안 버전 스펙 전체
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — 게이트 현황 및 향후 진행 사항
