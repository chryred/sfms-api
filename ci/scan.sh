#!/usr/bin/env bash
# CI 공통 — CVE 스캔 게이트 (DESIGN §9). High/Critical 발견 시 비0 종료로 빌드 실패.
# 1차 방어선: 소스/의존성 fs 스캔. 정밀 스캔은 이미지 스캔(build-image.sh)에서.
# 전제: 에이전트에 trivy 설치돼 있어야 함 (Bamboo 에이전트 capability로 보장).
set -euo pipefail
cd "$(dirname "$0")/.."
trivy fs --severity HIGH,CRITICAL --exit-code 1 --no-progress .
