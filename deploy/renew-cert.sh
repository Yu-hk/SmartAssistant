#!/usr/bin/env bash
set -euo pipefail

BASE="${SMART_ASSISTANT_BASE:-/opt/smart-assistant}"
DOMAIN="${SMART_ASSISTANT_DOMAIN:-xiaoyuai.cloud}"
CERTBOT_IMAGE="${CERTBOT_IMAGE:-docker.io/certbot/certbot:latest}"
LETSENCRYPT_DIR="$BASE/letsencrypt"
WEBROOT="$BASE/frontend/dist"
SSL_DIR="$BASE/deploy/nginx/ssl"
CERT_DIR="$LETSENCRYPT_DIR/live/$DOMAIN"

docker run --rm \
  -v "$WEBROOT:/var/www/certbot" \
  -v "$LETSENCRYPT_DIR:/etc/letsencrypt" \
  "$CERTBOT_IMAGE" renew --webroot -w /var/www/certbot --quiet "$@"

test -s "$CERT_DIR/fullchain.pem"
test -s "$CERT_DIR/privkey.pem"
install -d -m 0755 "$SSL_DIR"
install -m 0644 "$CERT_DIR/fullchain.pem" "$SSL_DIR/fullchain.pem.next"
install -m 0600 "$CERT_DIR/privkey.pem" "$SSL_DIR/privkey.pem.next"
mv -f "$SSL_DIR/fullchain.pem.next" "$SSL_DIR/fullchain.pem"
mv -f "$SSL_DIR/privkey.pem.next" "$SSL_DIR/privkey.pem"

docker exec smart-nginx nginx -t
docker exec smart-nginx nginx -s reload
