# api-private-router

`api-private-router` is an AI API relay and management platform with:

- admin console
- user center
- payment workflow
- OpenAI-compatible model gateway

## Stack

- Backend: Spring Boot, PostgreSQL, Redis
- Frontend: Vue 3, TypeScript, Vite, Pinia
- Deployment: Docker Compose, systemd, packaged Java service

## Layout

- `java-backend/`: Java backend, tests, migrations
- `frontend/`: admin and user web UI
- `deploy/`: Docker, systemd, env templates
- `docs/`: payment and integration docs
- `tools/`: repository scripts

## Quick Start

For deployment:

- Docker: `deploy/README.md`
- Rocky Linux 9: `docs/DEPLOY_ROCKY9.md`
- Java backend notes: `java-backend/README.md`

For local verification:

```bash
python tools/check_java_default_runtime.py
make test
make build
```

## Runtime Notes

- PostgreSQL is the supported database.
- Redis is required for cache, session, and async coordination.
- Admin, payment, user center, and gateway routes are served by the Java backend.
