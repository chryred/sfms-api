# SFMS API 서버 — 시스템 설계 문서

> React Native 클라이언트에서 호출하는 Java 기반 REST API 서버.
> Linux 배포, 최신 MVC 패턴, Oracle 19c(US7ASCII) + **MS949 ↔ ISO-8859-1 더블 인코딩**으로 한글 깨짐 방지.

---

## 0. 한 줄 요약

평범한 Spring Boot MVC 서버다. **유일하게 특별한 것은 DB 영속화 경계 한 곳에서만 일어나는 인코딩 변환**이며, 이 경계를 새지 않게 가두는 것이 설계의 전부다.

---

## 1. 기술 스택 (추천)

| 영역 | 선택 | 비고 |
|---|---|---|
| 언어/런타임 | **Java 21 LTS** (Temurin) | 드라이버 테스트 범위 + 장기 보안 패치. 상세 §8 |
| 프레임워크 | **Spring Boot 4.x (Spring MVC)** | "최신 MVC 패턴" = 모던 Java MVC. 3.x는 보안 지원 종료(§8) |
| 빌드 | **Gradle** (또는 Maven) | fat jar 산출 |
| 영속성 | **MyBatis (mybatis-spring-boot-starter)** | ⭐ 인코딩 훅을 위해 선택 (§4 참조) |
| DB 드라이버 | **ojdbc11** | Oracle 19c 지원 확인 |
| 커넥션 풀 | HikariCP | Boot 기본 |
| DB | **Oracle 19c (US7ASCII)** | 제약 조건(고정) |
| 배포 | Linux + **systemd** (또는 컨테이너) | fat jar 실행 |
| 클라이언트 | React Native | REST/JSON(UTF-8)로 통신 |

> 구체 버전·CVE 정책은 **§8 보안 버전 스펙** 참조.
> **JVM 옵션 필수**: `-Dfile.encoding=UTF-8`, 로케일 설정. 단, 변환 코드는 절대 기본 Charset에 의존하지 않고 항상 명시한다(§4).

---

## 2. 레이어 구조 (Controller → Service → Mapper)

```
┌─────────────────────────────────────────────────────────┐
│ React Native (UTF-8 JSON)                                │
└───────────────▲─────────────────────────────────────────┘
                │  REST / JSON / UTF-8  ← 인코딩 해킹 절대 없음
┌───────────────┴─────────────────────────────────────────┐
│ @RestController   (DTO, UTF-8 Java String = 정상 유니코드) │
├──────────────────────────────────────────────────────────┤
│ @Service          (비즈니스 로직, 정상 유니코드)            │
├──────────────────────────────────────────────────────────┤
│ MyBatis Mapper                                            │
│   └─ ★ EncodingTypeHandler ← 더블 인코딩이 사는 유일한 곳   │
├──────────────────────────────────────────────────────────┤
│ Oracle 19c (US7ASCII) — Latin-1로 재해석된 MS949 바이트 저장 │
└──────────────────────────────────────────────────────────┘
```

**핵심 원칙: 인코딩 변환은 DB 영속화 경계 한 곳에서만, 대칭적으로, 정확히 한 번.**
Controller/Service/JSON 레이어는 전부 평범한 Java 유니코드 / UTF-8 이다. 해킹이 HTTP 레이어로 새어 나가면 안 된다. (`getBytes`를 여기저기 흩뿌리는 것이 주니어가 가장 많이 하는 실수 — 금지.)

---

## 3. 인코딩 변환 명세 (정확성의 핵심)

### 3.1 변환 함수 — Charset은 항상 명시

```java
// 저장: 유니코드 String → (MS949 바이트) → ISO-8859-1로 재해석한 String
static String toDb(String s) {
    if (s == null) return null;
    return new String(s.getBytes("MS949"), "ISO-8859-1");
}

// 조회: DB의 Latin-1 String → (ISO-8859-1 바이트) → MS949로 디코딩한 정상 String
static String fromDb(String s) {
    if (s == null) return null;
    return new String(s.getBytes("ISO-8859-1"), "MS949");
}
```

> 절대 `file.encoding`/기본 Charset에 의존하지 않는다. 항상 `"MS949"`, `"ISO-8859-1"`를 문자열로 명시한다.

### 3.2 훅 위치 — MyBatis `TypeHandler` (추천)

한글 문자열 컬럼에만 커스텀 TypeHandler를 매핑하여, 변환을 컬럼 단위로 **명시적**으로 통제한다.

```java
@MappedTypes(String.class)
public class KoreanStringTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, toDb(parameter));        // 바인딩 시 인코딩
    }
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromDb(rs.getString(columnName));  // 조회 시 디코딩
    }
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromDb(rs.getString(columnIndex));
    }
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return fromDb(cs.getString(columnIndex));
    }
    // toDb/fromDb 는 §3.1
}
```

**대안**: JPA를 선호하면 `AttributeConverter<String,String>`로 동일 로직 구현 가능. 다만 이 레거시 한글/Oracle 시나리오에서는 **MyBatis + TypeHandler가 관용적**이고 컬럼 단위 통제·추론이 쉬워 기본 추천.

---

## 4. ⚠️ 시작 전 반드시 통과해야 할 게이트: 라운드트립 스모크 테스트

thin 드라이버가 US7ASCII DB에서 바이트 0x80–0xFF를 무손실로 왕복하는지는 **드라이버 버전 + `NLS_LANG` 조합에 따라 달라지며**, 이 해킹에는 여러 변종이 있다. 특정 NLS 레시피를 "사실"로 못 박지 않는다 — 데이터를 조용히 깨뜨리는 가장 흔한 원인.

**기능 개발 착수 전, 다음 게이트 테스트를 먼저 통과시킨다:**

```
"한글" 저장 → 다시 조회 → 원본과 동일(assertEquals) 인지 검증
```

이때 확정해야 할 설정 표면:
- `NLS_LANG` (클라이언트 환경변수)
- `ojdbc` 버전 (19c → **ojdbc11**)
- JVM `-Dfile.encoding=UTF-8`
- JDBC connection properties

> 이 테스트가 통과하기 전에는 어떤 기능도 만들지 않는다.

---

## 4.5 ⚠️ CLOB 한글 처리 — VARCHAR2와 **다른** 별도 게이트 (SFMS 해당)

> SFMS 레거시 스키마에 **한글 CLOB 컬럼이 존재**한다. **CLOB은 §3의 VARCHAR2 String 트릭이 그대로 통한다고 보장할 수 없다.** LOB은 charset 변환 경로가 VARCHAR2와 다르고, 동작이 드라이버 버전·접근 방식에 따라 갈린다(여러 변종 존재). 잘못하면 한글이 `?`로 치환되어 **복구 불가 손실** 발생.

### 왜 다른가
- CLOB 문자 메서드(`getCharacterStream`/`setCharacterStream`/`getString`)는 클라이언트 Unicode ↔ DB charset 변환을 수행. US7ASCII로의 변환에서 표현 불가 문자가 `?`로 소실될 수 있다.
- CLOB ASCII 스트림 메서드(`getAsciiStream`/`setAsciiStream`)는 US7ASCII/WE8ISO8859P1 호환을 가정 → 한글에서 깨진다(Oracle 문서 명시).
- 즉 VARCHAR2에서 통한 "ISO-8859-1 재해석 String" 트릭이 CLOB에서도 통할지는 **검증 전까지 미지수**다.

### 설계 원칙 — 단정하지 말고, 우선순위로 검증
**CLOB은 VARCHAR2와 별도의 TypeHandler + 별도의 라운드트립 게이트를 둔다.** 다음 후보를 **순서대로** 테스트해 SFMS의 드라이버/데이터에서 실제로 무손실인 것을 채택:

1. **(1순위) VARCHAR2와 동일한 String 트릭 재사용**: `getString`/`setString`(드라이버가 크기 따라 LOB 경로 자동 전환)에 `toDb/fromDb` 적용. 통하면 가장 단순 — 핸들러 공유.
2. **(2순위) 원시 바이트 접근**: CLOB을 바이트 스트림으로 읽어 **MS949로 직접 디코딩**(드라이버 charset 변환 우회). 1순위가 `?` 손실을 내면 채택.
3. 두 방식 모두 **기존 운영 CLOB 실데이터**(§6-1)로 왕복 검증해 기존 데이터 기록 방식과 일치하는지 확인.

### 신규 컬럼이라면 (참고)
새 한글 텍스트 컬럼이 필요하면 **NCLOB**(국가문자집합 AL16UTF16, US7ASCII와 무관)이 정공법이다. 단 **기존 CLOB 컬럼은 이미 해킹 인코딩으로 데이터가 들어있으므로** 타입 변경 없이 원래 기록 방식으로 읽어야 한다 — NCLOB은 신규 한정 옵션.

> **게이트 ①-B**: 위 CLOB 라운드트립이 통과하기 전에는 CLOB 관련 기능을 만들지 않는다.

---

## 5. 알려진 제약 (이 해킹의 본질적 한계 — 반드시 인지)

1. **VARCHAR2 사이징**: 저장형은 MS949 바이트(한글 2바이트)를 Latin-1로 재해석한 것. `VARCHAR2(n CHAR)` vs `(n BYTE)`와 US7ASCII 바이트 카운팅에 주의 — **바이트 여유분 필요**.
2. **ORDER BY / LIKE / 비교**: 깨진 Latin-1 바이트 기준으로 동작 → 언어학적으로 올바른 한글 정렬이 아니다. `LIKE '%한글%'`도 리터럴을 **동일하게 변환**해야 함 → **항상 바인드 파라미터(사전 변환)를 쓰고, 인라인 리터럴 금지.**
3. `LENGTH` ≠ `LENGTHB` — 값이 어긋난다.
4. **영향 범위(blast radius)**: SQL*Plus, BI 도구, 다른 서비스 등 이 컬럼을 만지는 모든 것은 동일 해킹을 적용하지 않으면 깨진 값을 본다. 문서화 필수.

---

## 6. 확인 필요 사항 (Open Questions)

1. **브라운필드 — 기존 레거시 DB로 확정 ★**: 기존 데이터가 이미 들어있는 DB이므로, 변환 스킴이 **기존 데이터가 기록된 방식과 정확히 일치**해야 한다. **MS949는 EUC-KR/KSC5601의 상위집합** — 레거시 행이 EUC-KR로 기록됐다면 MS949 디코딩은 대체로 맞지만 완벽히 호환되진 않는다.
   → **착수 전 필수**: 운영 테이블 실데이터 샘플을 `toDb/fromDb`로 왕복시켜 기존 한글이 깨지지 않는지 검증하고, EUC-KR/MS949 중 어느 스킴인지 확정한다.
2. "진짜 해법은 캐릭터셋(AL32UTF8) 마이그레이션"이지만 US7ASCII가 고정 제약이므로, 현 설계는 기존 해킹을 정확히 재현하는 데 집중한다.

---

## 8. 보안 버전 스펙 (CVE 최소화) — 2026-06 기준

> ⚠️ **정직한 전제**: "CVE 0건"을 영구히 단정할 수 있는 버전은 없다. CVE는 매일 새로 공개된다. 따라서 전략은 **① 현재 보안 지원(EOL 아님) 중인 최신 패치 버전으로 핀 고정 + ② 빌드 시 CVE 스캔을 게이트로 강제(§9)**. "스캔 통과 = 알려진 CVE 없음"을 보장한다.

### 8.1 권장 버전 (확정)

| 의존성 | 권장 버전 | 보안 근거 |
|---|---|---|
| **JDK** | **Eclipse Temurin 21 LTS** (최신 패치) | LTS·장기 보안 패치(4년+). ojdbc11이 명시적으로 테스트한 범위. Boot 4는 Java 17+ 지원 |
| **Spring Boot** | **4.1.0** (2026-06-10) | 현행 최신, 2027-07-31까지 보안 지원. ⚠️ **3.5는 2026-06-30 지원 종료** → 3.x 채택 금지 |
| **Spring Framework** | 7.0.8+ | Boot 4.1.0이 요구 (Boot BOM이 관리) |
| **MyBatis starter** | **mybatis-spring-boot-starter** (Boot 4 호환 라인) | 4.0.1은 Boot **4.0** 공식 지원. **4.1 호환은 §8.2 게이트로 검증** |
| **Oracle JDBC** | **ojdbc11 : 23.26.2.0.0** (2026-05-12) | 19c 지원 확인, 최신 패치. JDK 11/17/19/21 테스트 |
| **커넥션 풀 / Jackson / Logback / Tomcat 등** | **버전 명시 금지 — Spring Boot BOM 관리에 위임** | 직접 핀 고정하면 BOM의 보안 패치와 어긋남. `dependencyManagement`로 Boot가 일괄 관리 |

### 8.2 ⚠️ Spring Boot 4.0 vs 4.1 — MyBatis 호환 게이트 (결정 필요)

- **mybatis-spring-boot-starter 4.0.1**은 **Spring Boot 4.0** 공식 지원. Boot **4.1** 호환은 공식 명시가 없다(미검증).
- 두 가지 보안 지원 경로:
  - **경로 A (보안 런웨이 우선)**: **Boot 4.1.0** + MyBatis starter → 통합 테스트로 4.1 호환 확인. 지원 ~2027-07.
  - **경로 B (공식 호환 우선, 안전)**: **Boot 4.0.7** + mybatis-spring-boot-starter 4.0.1 (공식 지원 조합). 지원 ~2026-12 → MyBatis가 4.1 지원 릴리스하면 업그레이드.
- **권장**: 우선 **경로 B(4.0.7+4.0.1)** 로 안정 착수 → MyBatis의 Boot 4.1 지원 릴리스 시 4.1로 상향. (둘 다 현재 보안 지원 중)

> **✅ 결정(2026-06-23): 경로 B 채택** — Spring Boot **4.0.7** + mybatis-spring-boot-starter **4.0.1**. 공식 지원 조합으로 안정 착수, Boot 4.0 지원 종료(2026-12-31) 전 MyBatis의 4.1 지원 릴리스 시점에 4.1로 상향한다. **상향은 보안 런웨이 만료 전 필수 과제로 추적할 것.**

### 8.3 Java 21 vs 25

- Boot 4는 Java 25 LTS를 권장하지만, **ojdbc11의 명시적 테스트 범위는 JDK 21까지**다.
- 레거시 Oracle 연동·드라이버 안정성을 최우선으로 → **Java 21 LTS 1순위**. Java 25는 §8.2/§4 게이트에서 드라이버 왕복까지 검증된 뒤 상향 옵션으로 둔다.

---

## 9. CVE 스캔 게이트 (CI 강제)

빌드 파이프라인에 다음을 **차단 게이트**로 넣는다 — 통과 못 하면 머지/배포 불가.

1. **OWASP Dependency-Check** (또는 **Trivy** / **Grype**) — 의존성 트리 CVE 스캔.
   - 정책 예시: **CVSS ≥ 7.0(High) 발견 시 빌드 실패**.
2. **Dependabot / Renovate** — 신규 CVE·패치 자동 PR로 상시 최신 유지.
3. (선택) **SBOM 생성**(CycloneDX) — 공급망 추적.
4. 베이스 이미지 컨테이너 배포 시 **distroless 또는 최신 패치 JRE 이미지** 사용, 이미지도 Trivy 스캔.

> 이 게이트가 §1의 버전 표를 "현재 시점 CVE 없음"으로 실증한다. 버전 표는 출발점, 스캔이 보증.

---

## 10. 다음 단계

1. **(게이트 ①)** §4 VARCHAR2 라운드트립 스모크 테스트로 NLS/드라이버 설정 확정
2. **(게이트 ①-B)** §4.5 CLOB 라운드트립 — 후보 기법 우선순위 테스트로 무손실 방식 채택
3. **(게이트 ②)** §6-1 기존 실데이터 인코딩 확인·일치 (VARCHAR2 + CLOB 모두)
4. **(게이트 ③)** §8.2 Boot/MyBatis 조합 확정(✅ 경로 B) + §9 CVE 스캔 통과
5. 프로젝트 스캐폴딩 → `/sc:implement` 로 구현 착수

---

## 11. 설계 범위 점검 — 의도적으로 뺀 것 vs 진짜 빠진 것

이 문서는 **아키텍처 + 인코딩 해킹 + 보안 버전**에 집중했다. 아래는 아직 안 다룬 것들의 정직한 분류.

### 🔴 진짜 함정 — 착수 전 반드시 확인

1. **CLOB / 대용량 한글 컬럼 ✅ 설계 반영(§4.5)**: SFMS에 한글 CLOB 존재 확인 → VARCHAR2와 별도 핸들러·별도 게이트로 처리하도록 §4.5에 설계 완료. 구현 시 §4.5 우선순위 테스트로 무손실 방식 확정 필요.
2. **기존 레거시 스키마 역공학**: 브라운필드이므로 실제 테이블/컬럼/타입/제약을 먼저 확보해야 도메인 모델·매퍼를 짤 수 있다. 이게 구현의 1차 입력 — **아직 미확보**.

### 🟡 스코프 결정 필요

3. **API 엔드포인트 명세**: 도메인은 **시설물 관리(Facility Management)** 로 확인. 단 구체 엔드포인트는 관리 대상(시설물·점검·이력·자산 등)과 RN 화면 요구가 있어야 설계 가능 — 세부 요구 미확보.
4. **인증/인가**: RN ↔ API 인증 방식(JWT/세션/OAuth) 미정. 필요 여부·방식 결정 필요.

### 🟢 표준 — 구현 시 관용 패턴으로 처리(지금 설계 불필요)

5. 트랜잭션 경계(Service `@Transactional`), 표준 에러 응답 포맷(`@RestControllerAdvice`), 헬스체크/액추에이터, 로깅/감사. — 모두 Spring Boot 관용 패턴, 별도 설계 문서 불필요.
