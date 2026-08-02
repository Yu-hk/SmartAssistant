#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/smart-assistant}"
HOTFIX_DIR="${1:?hotfix directory is required}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${APP_DIR}/backups/${STAMP}-before-hotfix"

test "$HOTFIX_DIR" != "$APP_DIR"
test -d "$HOTFIX_DIR"
test -f "${APP_DIR}/deploy/.env"
test -f "${HOTFIX_DIR}/deploy/remote-rollout.sh"

install -d -m 0750 "$BACKUP_DIR"
cp -a "${APP_DIR}/deploy/.env" "${BACKUP_DIR}/deploy.env"
if test -f "${APP_DIR}/deploy/remote-rollout.sh"; then
  install -D -m 0750 \
    "${APP_DIR}/deploy/remote-rollout.sh" \
    "${BACKUP_DIR}/deploy/remote-rollout.sh"
fi

services=(router consumer order product)
for service in "${services[@]}"; do
  module="smart-assistant-${service}"
  jar="${module}-1.0.0-SNAPSHOT.jar"
  current="${APP_DIR}/${module}/target/${jar}"
  incoming="${HOTFIX_DIR}/${module}/target/${jar}"

  test -s "$incoming"
  install -D -m 0644 "$current" \
    "${BACKUP_DIR}/${module}/target/${jar}"
  install -m 0644 "$incoming" "$current"
done

install -m 0750 \
  "${HOTFIX_DIR}/deploy/remote-rollout.sh" \
  "${APP_DIR}/deploy/remote-rollout.sh"

echo "HOTFIX_INSTALLED"
echo "BACKUP_DIR=${BACKUP_DIR}"
