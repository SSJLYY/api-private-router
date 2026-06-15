# api-private-router

`api-private-router` 是一个 AI API 中转与管理平台，包含：

- 管理后台
- 用户中心
- 充值支付流程
- OpenAI 兼容模型网关

## 技术栈

- 后端：Spring Boot、PostgreSQL、Redis
- 前端：Vue 3、TypeScript、Vite、Pinia
- 部署：Docker Compose、systemd、Java 服务包

## 目录结构

- `java-backend/`：Java 后端、测试、数据库迁移
- `frontend/`：管理端与用户端页面
- `deploy/`：Docker、systemd、环境变量模板
- `docs/`：支付与集成文档
- `tools/`：仓库脚本

## 快速入口

部署文档：

- Docker：`deploy/README.md`
- Rocky Linux 9：`docs/DEPLOY_ROCKY9.md`
- Java 后端说明：`java-backend/README.md`

本地校验：

```bash
python tools/check_java_default_runtime.py
make test
make build
```

> **Windows 用户**：Windows 系统没有 `make` 命令，请参考 `DEV_GUIDE.md` 中的替代命令。

## 运行说明

- 当前支持的数据库为 `PostgreSQL`
- `Redis` 用于缓存、会话与异步协调
- 管理后台、支付、用户中心与网关接口均由 Java 后端提供
