#!/usr/bin/env bash
# 本地构建镜像的快速脚本，避免在命令行反复输入构建参数。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

docker build -t api-private-router:latest \
    --build-arg APP_VERSION=local \
    -f "${REPO_ROOT}/Dockerfile" \
    "${REPO_ROOT}"
