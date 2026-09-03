#!/usr/bin/env bash
#
# One-time setup of the OSADA host. Run on the VPS as root:
#
#   bash bootstrap-server.sh
#
# Safe to re-run: every step is idempotent. The room server sits on 127.0.0.1:8090 behind nginx on :80.
set -euo pipefail

APP_USER=osada
APP_DIR=/opt/osada

echo "== system packages"
export DEBIAN_FRONTEND=noninteractive
if ! command -v nginx >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y -qq nginx
else
    echo "nginx already installed"
fi

echo "== service account"
if ! id -u "$APP_USER" >/dev/null 2>&1; then
    useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
else
    echo "user $APP_USER already exists"
fi

echo "== directories"
mkdir -p "$APP_DIR/server" "$APP_DIR/web"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

echo "== nginx site"
install -m 0644 /tmp/osada-deploy/nginx-osada.conf /etc/nginx/sites-available/osada
ln -sfn /etc/nginx/sites-available/osada /etc/nginx/sites-enabled/osada
# Ubuntu's packaged default site also claims `default_server` on :80.
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx || systemctl restart nginx
systemctl enable nginx >/dev/null

echo "== systemd unit"
# The tracked unit carries a placeholder instead of this host's address; deploy.sh passes the origin
# the game will actually be served from, so the Origin allowlist is filled in without the public
# repository naming the server. An empty OSADA_ORIGIN leaves the allowlist open.
install -m 0644 /tmp/osada-deploy/osada.service /etc/systemd/system/osada.service
sed -i "s|__OSADA_ORIGIN__|${OSADA_ORIGIN:-}|" /etc/systemd/system/osada.service
if [ -z "${OSADA_ORIGIN:-}" ]; then
    echo "   warning: OSADA_ALLOWED_ORIGINS is empty — any website may open rooms on this server"
fi
systemctl daemon-reload
systemctl enable osada >/dev/null

echo "== firewall"
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp >/dev/null
    # Opened now so a later `certbot --nginx` does not fail on a blocked port.
    ufw allow 443/tcp >/dev/null
    ufw status
fi

echo "== done. Run deploy.sh to install the game build."
