#!/usr/bin/env bash
# Serve a Google Drive rclone remote as WebDAV on the LAN for the depth3d
# player, and announce it over Bonjour (_webdav._tcp) so the headset finds it.
#
#   scripts/serve-gdrive.sh [remote:path] [port]
#
# Login is user "depth3d" with a password kept in ~/.config/depth3d/webdav-pass
# (made on first run). The player asks for it once.
set -euo pipefail
REMOTE="${1:-gdrive:}"
PORT="${2:-8890}"
CONF="$HOME/.config/depth3d"
mkdir -p "$CONF"
if [[ ! -s "$CONF/webdav-pass" ]]; then
  openssl rand -hex 4 >"$CONF/webdav-pass"
  chmod 600 "$CONF/webdav-pass"
fi
PASS="$(cat "$CONF/webdav-pass")"
IP="$(ipconfig getifaddr en0 || ipconfig getifaddr en1 || echo '?')"
echo "WebDAV: http://$IP:$PORT/  user depth3d  password $PASS"

dns-sd -R "depth3d $(scutil --get ComputerName)" _webdav._tcp local "$PORT" path=/ >/dev/null &
DNSSD=$!
trap 'kill $DNSSD 2>/dev/null' EXIT

# Read-only, no disk cache: video is read in chunks that grow from 16M, and
# a seek just opens the file again at the new offset.
exec rclone serve webdav "$REMOTE" \
  --addr ":$PORT" --user depth3d --pass "$PASS" \
  --read-only --vfs-cache-mode off \
  --vfs-read-chunk-size 16M --vfs-read-chunk-size-limit 256M \
  --buffer-size 32M --dir-cache-time 5m --poll-interval 0
