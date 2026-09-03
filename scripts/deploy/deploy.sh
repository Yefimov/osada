#!/usr/bin/env bash
#
# Build OSADA locally and install it on the VPS.
#
#   scripts/deploy/deploy.sh                 # code + small web files (fast, the usual case)
#   scripts/deploy/deploy.sh --assets        # also re-upload resources/ (~800 MB: art, maps, scenarios)
#   scripts/deploy/deploy.sh --bootstrap --assets   # first ever deploy
#   scripts/deploy/deploy.sh --skip-build    # reuse the last local build
#   scripts/deploy/deploy.sh --reuse-archives --assets   # retry an upload that dropped
#
# Runs from Git Bash on Windows; it needs only ssh, scp and tar. Nothing is kept on the server
# except the unpacked build — every uploaded archive is deleted at the end.
#
# The link to the VPS drops when a VPN is active on this machine, and scp cannot resume. Archives
# are therefore written to build/deploy/ and --reuse-archives skips repacking them, so a retry
# costs only the transfer.
set -euo pipefail

APP_DIR=/opt/osada
STAGE=/tmp/osada-deploy

# Where to deploy. This repository is public, so the host is not written down in it: put
# "user@host" in scripts/deploy/target.local (git-ignored) or pass OSADA_REMOTE.
TARGET_FILE="$(dirname "${BASH_SOURCE[0]}")/target.local"
REMOTE="${OSADA_REMOTE:-$(cat "$TARGET_FILE" 2>/dev/null | tr -d '\r\n')}"
if [ -z "$REMOTE" ]; then
    echo "No deployment target. Write user@host into $TARGET_FILE or set OSADA_REMOTE." >&2
    exit 2
fi

# The route to this host is unreliable (it gets much worse with a VPN enabled on this machine), so
# every hop is given keepalives and several connection attempts before it is called a failure.
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=20 -o ConnectionAttempts=4
          -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
SSH_RETRIES="${OSADA_SSH_RETRIES:-6}"

# ssh reports its own failures (refused, timed out, dropped mid-session) as 255; any other status
# belongs to the remote command and is passed through untouched. Every remote step this script runs
# is idempotent, so retrying a broken connection is always safe.
ssh() {
    local attempt status
    for attempt in $(seq 1 "$SSH_RETRIES"); do
        command ssh "${SSH_OPTS[@]}" "$@" && return 0
        status=$?
        [ "$status" -ne 255 ] && return "$status"
        [ "$attempt" -eq "$SSH_RETRIES" ] && return 255
        sleep 5
    done
}

scp() {
    local attempt
    for attempt in $(seq 1 "$SSH_RETRIES"); do
        command scp "${SSH_OPTS[@]}" "$@" && return 0
        [ "$attempt" -eq "$SSH_RETRIES" ] && return 1
        sleep 5
    done
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEB_BUILD="$ROOT/build/dist/js/productionExecutable"
SERVER_BUILD="$ROOT/multiplayer-server/build/install/osada-server"
WORK="$ROOT/build/deploy"
mkdir -p "$WORK"

with_assets=0
bootstrap=0
skip_build=0
reuse_archives=0
for argument in "$@"; do
    case "$argument" in
        --assets) with_assets=1 ;;
        --bootstrap) bootstrap=1 ;;
        --skip-build) skip_build=1 ;;
        --reuse-archives) reuse_archives=1; skip_build=1 ;;
        *) echo "unknown option: $argument" >&2; exit 2 ;;
    esac
done

if [ "$skip_build" -eq 0 ]; then
    echo "== building"
    (cd "$ROOT" && ./gradlew jsBrowserDistribution :multiplayer-server:installDist --console=plain -q)
fi
[ -d "$WEB_BUILD" ] || { echo "missing $WEB_BUILD" >&2; exit 1; }
[ -d "$SERVER_BUILD" ] || { echo "missing $SERVER_BUILD" >&2; exit 1; }

if [ "$reuse_archives" -eq 1 ] && [ -f "$WORK/osada-app.tar.gz" ]; then
    echo "== reusing $WORK/osada-app.tar.gz"
else
    echo "== packing application archive"
    # Everything except resources/: the JVM server, the game bundle, styles, locales, portraits.
    # The .map file is 3.4 MB of source map that only helps when debugging a production stack trace.
    tar -czf "$WORK/osada-app.tar.gz" \
        -C "$(dirname "$SERVER_BUILD")" --transform 's,^osada-server,server,' osada-server \
        -C "$WEB_BUILD" --transform 's,^\.,web,' --exclude=./resources --exclude=./osada.js.map .
fi

if [ "$with_assets" -eq 1 ]; then
    if [ "$reuse_archives" -eq 1 ] && [ -f "$WORK/osada-assets.tar.gz" ]; then
        echo "== reusing $WORK/osada-assets.tar.gz"
    else
        echo "== packing asset archive (this takes a while)"
        # gzip -1: unit art and maps are already-compressed PNG/JPG, so a higher level only burns CPU.
        tar -cf - -C "$WEB_BUILD" --transform 's,^resources,web/resources,' \
            --exclude='resources/_unused_assets' resources |
            gzip -1 > "$WORK/osada-assets.tar.gz"
    fi
fi

# scp cannot resume, and the link to this host drops often enough that an 800 MB single stream
# rarely survives. Archives go up in 64 MB parts instead: a part already on the server with a
# matching checksum is skipped, so a retry only moves what is actually missing.
UPLOAD_ATTEMPTS="${OSADA_UPLOAD_ATTEMPTS:-20}"

upload_archive() {
    local file="$1"
    local name
    name="$(basename "$file")"
    rm -f "$WORK/$name".part.*
    split -b 64m -d -a 3 "$file" "$WORK/$name.part."

    local remote_sums
    remote_sums="$(ssh "$REMOTE" "md5sum $STAGE/$name.part.* 2>/dev/null" || true)"

    local part base local_sum
    for part in "$WORK/$name".part.*; do
        base="$(basename "$part")"
        local_sum="$(md5sum "$part" | cut -d' ' -f1)"
        if printf '%s' "$remote_sums" | grep -q "^$local_sum  $STAGE/$base\$"; then
            echo "   $base already uploaded"
            continue
        fi
        local attempt sent=0
        for attempt in $(seq 1 "$UPLOAD_ATTEMPTS"); do
            if scp -q "$part" "$REMOTE:$STAGE/"; then
                echo "   $base sent"
                sent=1
                break
            fi
            echo "   $base failed (attempt $attempt/$UPLOAD_ATTEMPTS), retrying" >&2
            sleep 10
        done
        if [ "$sent" -eq 0 ]; then
            echo "!! could not upload $base; rerun with --reuse-archives to continue" >&2
            exit 1
        fi
    done

    ssh "$REMOTE" "cat $STAGE/$name.part.* > $STAGE/$name && rm -f $STAGE/$name.part.*"
    rm -f "$WORK/$name".part.*
}

echo "== uploading"
ssh "$REMOTE" "mkdir -p $STAGE"
scp -q "$ROOT/scripts/deploy/osada.service" "$ROOT/scripts/deploy/nginx-osada.conf" \
    "$ROOT/scripts/deploy/bootstrap-server.sh" "$ROOT/scripts/deploy/install-remote.sh" \
    "$REMOTE:$STAGE/"
upload_archive "$WORK/osada-app.tar.gz"
# Not `[ ... ] && upload_archive`: under `set -e` a false test as the last command of a list would
# end the script, so a code-only deploy would silently stop right here.
if [ "$with_assets" -eq 1 ]; then
    upload_archive "$WORK/osada-assets.tar.gz"
fi

if [ "$bootstrap" -eq 1 ]; then
    echo "== bootstrapping host"
    # The origin players will load the game from, which becomes the socket Origin allowlist. Set
    # OSADA_ORIGIN explicitly once the site has a domain and certificate.
    origin="${OSADA_ORIGIN:-http://${REMOTE#*@}}"
    ssh "$REMOTE" "OSADA_ORIGIN='$origin' bash $STAGE/bootstrap-server.sh"
fi

echo "== installing"
ssh "$REMOTE" "bash $STAGE/install-remote.sh"

echo "== health check"
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if ssh "$REMOTE" "curl -fsS http://127.0.0.1:8090/healthz" 2>/dev/null; then
        echo
        echo "== deployed: http://${REMOTE#*@}/"
        exit 0
    fi
    sleep 2
done

echo "!! the server did not answer /healthz; last log lines:" >&2
ssh "$REMOTE" "journalctl -u osada -n 40 --no-pager" >&2
exit 1
