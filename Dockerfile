# syntax=docker/dockerfile:1
# SFMS API — 멀티스테이지 컨테이너 빌드 (DESIGN §1 배포 / docs/DEPLOY.md)
# ⚠️ 전제: @SpringBootApplication 진입점이 있어야 bootJar가 생성됨(ROADMAP 스캐폴딩).

### 1단계: 빌드 (JDK 21 + Gradle Wrapper) ###
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# 의존성 레이어 캐시: 빌드 스크립트 먼저 복사
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
# 소스 복사 후 fat jar 빌드
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar \
 && cp build/libs/*.jar /workspace/app.jar

### 2단계: 런타임 (JRE 21, 비루트) ###
FROM eclipse-temurin:21-jre AS runtime
# 로케일/타임존 + 인코딩 (한글 안전)
ENV TZ=Asia/Seoul \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
# 비루트 실행 (보안)
RUN useradd -r -u 1001 appuser
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER appuser
EXPOSE 8080
# 컨테이너 친화 JVM 옵션은 21 JRE 기본값으로 충분(MaxRAMPercentage 등 필요시 추가)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
