#!/bin/bash
set -e

BASE_DIR="/data/api-private-router"
FRONTEND_DIR="$BASE_DIR/frontend"

echo "==> 清理 Vite 缓存..."
rm -rf "$FRONTEND_DIR/node_modules/.vite"

echo "==> 构建前端..."
cd "$FRONTEND_DIR"
pnpm run build

echo "==> 启动开发服务器..."
pnpm run dev

