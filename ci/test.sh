#!/usr/bin/env bash
# CI 공통 — 테스트 (게이트 ① 단위, DESIGN §4). 엔진 무관: Bamboo/GitHub/Jenkins 공용.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon test
