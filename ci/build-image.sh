#!/usr/bin/env bash
# CI 공통 — 이미지 빌드 + 이미지 CVE 게이트 + push. 엔진 무관.
# ⚠️ 전제: @SpringBootApplication 진입점이 있어야 bootJar/이미지 빌드 가능(ROADMAP).
#         자격증명은 CI 시크릿으로 사전 로그인했다고 가정(이 스크립트는 push만).
set -euo pipefail
cd "$(dirname "$0")/.."

: "${IMAGE:?IMAGE=레지스트리/이름:태그 형태로 지정 필요 (예: registry.사내/sfms-api:123)}"

# docker 또는 podman 자동 선택 (Docker 종속 회피)
ENGINE="$(command -v podman || command -v docker)"
echo "[build-image] using engine: ${ENGINE}"

"${ENGINE}" build -t "${IMAGE}" .

# 이미지 CVE 게이트 (본 게이트 — DESIGN §9)
trivy image --severity HIGH,CRITICAL --exit-code 1 --no-progress "${IMAGE}"

"${ENGINE}" push "${IMAGE}"
