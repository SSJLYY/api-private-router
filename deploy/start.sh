#!/bin/bash
set -e

BASE_DIR="/data/api-private-router"
JAVA_BACKEND_DIR="$BASE_DIR/java-backend"
JAR_FILE="$JAVA_BACKEND_DIR/target/api-private-router.jar"
JAVA_HOME="/data/java/jdk21"
JAVA_CMD="$JAVA_HOME/bin/java"
MVN_CMD="mvn"

# ===== 环境变量 =====
export TOTP_ENCRYPTION_KEY=3acdf0c2aac8a09e02f77b95ccacd83c27a084f0a43bf7312ae6cfaea824e420
export JWT_SECRET=fd874400de7ba1b1f6df7189f73f7b3fd4eb18746e3d93bb16842fd3250e9332
export DATABASE_PASSWORD=change_this_secure_password
export REDIS_HOST=192.168.215.116
export DATABASE_HOST=192.168.215.116
export DATABASE_USER=api_private_router
export DATABASE_DBNAME=api_private_router
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
git fetch origin main
git reset --hard origin/main
git clean -fd -e data/ -e java-backend/data/ -e java-backend/target/ -e frontend/node_modules/ -e frontend/dist/
log "     完成"

step "2/4 打包后端.."
cd "$JAVA_BACKEND_DIR"
JAVA_HOME="$JAVA_HOME" $MVN_CMD clean package -DskipTests -q
log "     完成"

step "3/4 停止旧进程.."
fuser -k 8080/tcp 2>/dev/null || true
sleep 2
log "     完成"

step "4/4 启动新服务.."
nohup $JAVA_CMD -jar "$JAR_FILE" > "$JAVA_BACKEND_DIR/app.log" 2>&1 &
NEW_PID=$!
log "     已启动 PID $NEW_PID"

# 等待启动
sleep 3
if kill -0 "$NEW_PID" 2>/dev/null; then
  log "     启动成功 (PID $NEW_PID)"
else
  log "     启动失败，查看日志: tail -f $JAVA_BACKEND_DIR/app.log"
  exit 1
fi

log "========================================"
log "全部完成！日志文件: $JAVA_BACKEND_DIR/app.log"
log "实时日志: tail -f $JAVA_BACKEND_DIR/app.log"
