#!/usr/bin/env bash
#
# Installs an uploaded build. Run on the VPS by deploy.sh; expects the archives in /tmp/osada-deploy.
#
# Idempotent on purpose: the link to this host drops often, so deploy.sh may run this more than once.
set -euo pipefail

APP_DIR=/opt/osada
STAGE=/tmp/osada-deploy

systemctl stop osada 2>/dev/null || true

rm -rf "$APP_DIR/server"
# Replace the web build but keep resources/, which travels in its own archive and is usually
# already correct.
if [ -d "$APP_DIR/web" ]; then
    find "$APP_DIR/web" -mindepth 1 -maxdepth 1 ! -name resources -exec rm -rf {} +
fi
mkdir -p "$APP_DIR/web"
tar -xzf "$STAGE/osada-app.tar.gz" -C "$APP_DIR"

if [ -f "$STAGE/osada-assets.tar.gz" ]; then
    rm -rf "$APP_DIR/web/resources"
    tar -xzf "$STAGE/osada-assets.tar.gz" -C "$APP_DIR"
fi

chown -R osada:osada "$APP_DIR"
chmod +x "$APP_DIR/server/bin/osada-server"
rm -rf "$STAGE"

systemctl start osada
echo "installed"
