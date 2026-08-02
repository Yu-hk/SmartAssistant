#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/smart-assistant}"
RELEASE_DIR="${1:?release directory is required}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${APP_DIR}/backups/${STAMP}-before-rollout"

test "$RELEASE_DIR" != "$APP_DIR"
test -d "$RELEASE_DIR"
test -f "${APP_DIR}/deploy/.env"
test -f "${RELEASE_DIR}/deploy/remote-rollout.sh"
test -f "${RELEASE_DIR}/deploy/nginx/default.conf"
test -d "${RELEASE_DIR}/frontend/dist"

# 模型约 1.3GB，常规应用发布复用服务器已有模型，避免每次重复上传。
# 只有发布包显式携带 models/ 时才替换；首次部署仍要求目标目录已有模型。
if test ! -d "${RELEASE_DIR}/models"; then
  test -s "${APP_DIR}/models/bge-large-zh-v1.5.onnx.data"
fi

install -d -m 0750 "$BACKUP_DIR"
cp -a "${APP_DIR}/deploy/.env" "${BACKUP_DIR}/deploy.env"

for relative in \
  deploy/nginx/default.conf \
  deploy/remote-start.sh \
  deploy/remote-rollout.sh; do
  if test -e "${APP_DIR}/${relative}"; then
    install -D -m 0644 \
      "${APP_DIR}/${relative}" "${BACKUP_DIR}/${relative}"
  fi
done

services=(
  gateway
  user
  router
  consumer
  order
  product
  general
  embedding-service
)

for service in "${services[@]}"; do
  module="smart-assistant-${service}"
  jar="${module}-1.0.0-SNAPSHOT.jar"
  current="${APP_DIR}/${module}/target/${jar}"
  incoming="${RELEASE_DIR}/${module}/target/${jar}"

  test -s "$incoming"
  install -d -m 0755 "${APP_DIR}/${module}/target"
  if test -e "$current"; then
    install -D -m 0644 "$current" \
      "${BACKUP_DIR}/${module}/target/${jar}"
  fi
  install -m 0644 "$incoming" "$current"
done

if test -d "${APP_DIR}/frontend/dist"; then
  mv "${APP_DIR}/frontend/dist" "${BACKUP_DIR}/frontend-dist"
fi
mv "${RELEASE_DIR}/frontend/dist" "${APP_DIR}/frontend/dist"

if test -d "${RELEASE_DIR}/models"; then
  if test -d "${APP_DIR}/models"; then
    mv "${APP_DIR}/models" "${BACKUP_DIR}/models"
  fi
  mv "${RELEASE_DIR}/models" "${APP_DIR}/models"
fi

install -m 0644 \
  "${RELEASE_DIR}/deploy/nginx/default.conf" \
  "${APP_DIR}/deploy/nginx/default.conf"
install -m 0750 \
  "${RELEASE_DIR}/deploy/remote-rollout.sh" \
  "${APP_DIR}/deploy/remote-rollout.sh"

echo "RELEASE_INSTALLED"
echo "BACKUP_DIR=${BACKUP_DIR}"
