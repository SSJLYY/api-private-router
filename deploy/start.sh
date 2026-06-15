#!/bin/bash
set -e

BASE_DIR="/data/api-private-router"
JAVA_BACKEND_DIR="$BASE_DIR/java-backend"
JAR_FILE="$JAVA_BACKEND_DIR/target/api-private-router.jar"
JAVA_HOME="/data/java/jdk17"
JAVA_CMD="$JAVA_HOME/bin/java"
MVN_CMD="mvn"

# ===== 环境变量 =====
# 从 .env 文件加载配置（需预先配置）
if [ -f "$BASE_DIR/.env" ]; then
  set -a
  source "$BASE_DIR/.env"
  set +a
fi

# 必需的环境变量检查
: "${TOTP_ENCRYPTION_KEY:?TOTP_ENCRYPTION_KEY is required}"
: "${JWT_SECRET:?JWT_SECRET is required}"
: "${DATABASE_PASSWORD:?DATABASE_PASSWORD is required}"
: "${REDIS_HOST:?REDIS_HOST is required}"
: "${DATABASE_HOST:?DATABASE_HOST is required}"
export DATABASE_USER=${DATABASE_USER:-api_private_router}
export DATABASE_DBNAME=${DATABASE_DBNAME:-api_private_router}
export SECURITY_URL_ALLOWLIST_ENABLED=false
export SECURITY_URL_ALLOWLIST_ALLOW_PRIVATE_HOSTS=true
export SECURITY_URL_ALLOWLIST_ALLOW_INSECURE_HTTP=true

# 仅在首次安装时取消注释下面这行
# export AUTO_SETUP=true

log() {
  local msg="[$(date '+%H:%M:%S')] $1"
  echo "$msg"
}

step() {
  log "==> $1"
}

step "1/4 拉取最新代码.."
cd "$BASE_DIR"
if ! git diff --quiet || ! git diff --cached --quiet; then
  log "WARNING: Uncommitted changes detected. Stashing before update..."
  git stash push -m "auto-stash before start.sh update"
fi
git fetch origin main
git reset --hard origin/main
git clean -fd -e data/ -e java-backend/data/ -e java-backend/target/ -e frontend/node_modules/ -e frontend/dist/
log "     完成"

step "2/4 打包后端.."
cd "$JAVA_BACKEND_DIR"
JAVA_HOME="$JAVA_HOME" $MVN_CMD clean package -DskipTests -q
log "     完成"

step "3/4 停止旧进程.."
PORT_TO_KILL="${SERVER_PORT:-8080}"
fuser -k "${PORT_TO_KILL}/tcp" 2>/dev/null || true
sleep 2
log "     完成"

step "4/4 启动新服务.."
nohup $JAVA_CMD -jar "$JAR_FILE" > "$JAVA_BACKEND_DIR/app.log" 2>&1 &
NEW_PID=$!
log "     已启动 PID $NEW_PID"

# 等待启动 (最多 30 秒)
for i in $(seq 1 15); do
  if ! kill -0 "$NEW_PID" 2>/dev/null; then
    log "     启动失败，查看日志: tail -f $JAVA_BACKEND_DIR/app.log"
    exit 1
  fi
  # 检查健康端口是否就绪
  if curl -sf "http://localhost:${SERVER_PORT:-8080}/health" >/dev/null 2>&1; then
    log "     启动成功 (PID $NEW_PID)"
    exit 0
  fi
  sleep 2
done
log "     启动超时，进程仍在运行 (PID $NEW_PID)，请检查日志: tail -f $JAVA_BACKEND_DIR/app.log"

log "========================================"
log "全部完成！日志文件: $JAVA_BACKEND_DIR/app.log"
log "实时日志: tail -f $JAVA_BACKEND_DIR/app.log"
