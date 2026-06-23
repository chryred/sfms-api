#!/usr/bin/env bash
# CI 공통 — CVE 스캔 게이트 (DESIGN §9). High/Critical 발견 시 비0 종료로 빌드 실패.
# ⚠️ 현재 상태: gradle.lockfile 부재로 fs 스캔이 의존성을 못 읽음 → 사실상 placeholder.
#    의존성 잠금은 의도적 보류(결정 B, ROADMAP 참조). 의존성 안정 후 잠그면 실질화됨.
#    그 전까지 진짜 게이트는 이미지 스캔(build-image.sh의 trivy image).
# 전제: 에이전트에 trivy 설치돼 있어야 함 (Bamboo 에이전트 capability로 보장).
set -euo pipefail
cd "$(dirname "$0")/.."
trivy fs --severity HIGH,CRITICAL --exit-code 1 --no-progress .
