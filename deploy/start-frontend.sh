#!/bin/bash
set -e

BASE_DIR="${BASE_DIR:-/data/api-private-router}"
FRONTEND_DIR="$BASE_DIR/frontend"
FRONTEND_PORT="${FRONTEND_PORT:-${VITE_DEV_PORT:-3000}}"

log() {
  echo "[$(date '+%H:%M:%S')] $1"
}

step() {
  log "==> $1"
}

step "1/4 Clean Vite cache.."
rm -rf "$FRONTEND_DIR/node_modules/.vite"
log "     Done"

step "2/4 Build frontend.."
cd "$FRONTEND_DIR"
pnpm run build
log "     Done"

step "3/4 Stop old frontend process.."
if command -v fuser >/dev/null 2>&1; then
  fuser -k "${FRONTEND_PORT}/tcp" 2>/dev/null || true
elif command -v lsof >/dev/null 2>&1; then
  old_pids=$(lsof -ti tcp:"$FRONTEND_PORT" 2>/dev/null || true)
  if [ -n "$old_pids" ]; then
    kill $old_pids 2>/dev/null || true
  fi
fi
sleep 2
log "     Done"

step "4/4 Start frontend dev server.."
export VITE_DEV_PORT="$FRONTEND_PORT"
exec pnpm run dev
