#!/usr/bin/env bash
# MedPad Domain Setup — auto-installs cloudflared + runs the wizard (v1.1)
# Usage:
#   export CLOUDFLARE_API_TOKEN='...'   # Tunnel:Edit + DNS:Edit + Zone:Read
#   ./setup_domain.sh                   # interactive
#   ./setup_domain.sh example.com panel http://localhost:3000
set -e
cd "$(dirname "$0")"

echo "━━━ MedPad Domain Setup ━━━"

if ! command -v python3 >/dev/null 2>&1; then
  echo "✗ python3 نصب نیست"; exit 1
fi

ARCH=$(uname -m)
case "$ARCH" in
  x86_64) CF_ARCH="amd64" ;;
  aarch64|arm64) CF_ARCH="arm64" ;;
  *) echo "✗ معماری پشتیبانی نمی‌شود: $ARCH"; exit 1 ;;
esac

if [ ! -x "./cloudflared" ]; then
  echo "⬇ دانلود cloudflared (${CF_ARCH})…"
  curl -sL -o cloudflared \
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}"
  chmod +x cloudflared
fi

DOMAIN="${1:-}"
SUB="${2:-panel}"
SERVICE="${3:-http://localhost:3000}"

if [ -z "$DOMAIN" ]; then
  read -rp "🌐 دامنه (مثلا example.com): " DOMAIN
fi
if [ -z "$CLOUDFLARE_API_TOKEN" ]; then
  echo "🔑 توکن API لازم است (Tunnel:Edit + DNS:Edit + Zone:Read)"
  read -rsp "Cloudflare API Token: " CLOUDFLARE_API_TOKEN
  echo
  export CLOUDFLARE_API_TOKEN
fi

python3 domain_setup_wizard.py --non-interactive \
  --domain "$DOMAIN" --subdomain "$SUB" --service "$SERVICE" --protocol http2

echo
echo "▶ اجرای تونل (پنجره را باز نگه دارید):"
echo "  ./cloudflared tunnel --config config.yml run"
