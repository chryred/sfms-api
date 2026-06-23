#!/usr/bin/env bash
# 서버 배포 스크립트 — CD가 만든 이미지 tar를 적재하고 Quadlet 서비스를 갱신·재기동.
#
# 전제(최초 1회): deploy/sfms-api.container 를 Quadlet 경로에 설치 + systemd 등록.
#   sudo cp deploy/sfms-api.container /etc/containers/systemd/
#   sudo systemctl daemon-reload && sudo systemctl start sfms-api
#   podman secret create sfms-db-password -   # DB 비밀번호 1회 등록
#
# 사용법: ./load-and-restart.sh <image-tar> [service-name]
#   예)   ./load-and-restart.sh sfms-api-0.0.2.tar
#
# 동작: tar 적재 → 적재된 이미지를 localhost/sfms-api:current 로 태깅
#       → daemon-reload → restart. (.container 의 Image=:current 는 고정 유지)
# 롤백: podman tag localhost/sfms-api:<이전버전> localhost/sfms-api:current && sudo systemctl restart sfms-api
set -euo pipefail

TAR="${1:?사용법: $0 <image-tar> [service-name]}"
SERVICE="${2:-sfms-api}"
STABLE_TAG="localhost/sfms-api:current"

echo "[1/4] 이미지 적재: ${TAR}"
LOADED="$(podman load -i "${TAR}" | sed -n 's/.*Loaded image: *//p' | head -1)"
[ -n "${LOADED}" ] || { echo "ERROR: 적재된 이미지 이름을 못 읽음"; exit 1; }
echo "      loaded: ${LOADED}"

echo "[2/4] 안정 태그 지정: ${STABLE_TAG}"
podman tag "${LOADED}" "${STABLE_TAG}"

echo "[3/4] Quadlet 반영 + 재기동"
# rootless면: systemctl --user daemon-reload && systemctl --user restart "${SERVICE}"
sudo systemctl daemon-reload
sudo systemctl restart "${SERVICE}"

echo "[4/4] 상태"
systemctl status "${SERVICE}" --no-pager || true
echo "현재 실행 버전 후보: ${LOADED}"
