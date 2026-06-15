# api-private-router Deployment

This directory contains the deployment assets for the Java runtime used by api-private-router.

## Files

- `docker-compose.local.yml`: Docker Compose with local directories for state
- `docker-compose.yml`: Docker Compose with named Docker volumes
- `docker-compose.dev.yml`: Docker Compose for development (local build)
- `docker-compose.standalone.yml`: Docker Compose for standalone mode (external DB/Redis)
- `Dockerfile`: Container build definition
- `.env.example`: environment template
- `config.example.yaml`: application config template
- `install.sh`: Java service installation helper
- `api-private-router.service`: systemd unit file
- `docker-entrypoint.sh`: container bootstrap logic
- `docker-deploy.sh`: local deployment helper script
- `build_image.sh`: Docker image build script
- `Makefile`: Deploy-specific make targets
- `DOCKER.md`: Docker deployment documentation

## Deployment Paths

- Use `docker-compose.local.yml` when you want portable state in `data/`, `postgres_data/`, and `redis_data/`.
- Use `docker-compose.yml` when you prefer Docker-managed named volumes.
- Use `install.sh` and `api-private-router.service` for a systemd-based Java deployment.

For a host-level Rocky Linux 9 walkthrough, see `docs/DEPLOY_ROCKY9.md`.

## Docker Quick Start

```bash
cd deploy
cp .env.example .env
mkdir -p data postgres_data redis_data
docker compose -f docker-compose.local.yml up -d
docker compose -f docker-compose.local.yml logs -f api-private-router
```

Before first start, review `.env` and set at least:

- `POSTGRES_PASSWORD`
- `JWT_SECRET`
- `TOTP_ENCRYPTION_KEY`

Optional but commonly adjusted:

- `POSTGRES_USER`
- `POSTGRES_DB`
- `ADMIN_EMAIL`
- `ADMIN_PASSWORD`
- `SERVER_PORT`
- `TZ`

If `ADMIN_PASSWORD` is left empty, the first boot path may generate one automatically. Check the service logs after startup.

## Java Service Path

1. Prepare JDK 17, PostgreSQL, and Redis.
2. Review `config.example.yaml` and adapt it for the target environment.
3. Build the backend from `../java-backend/`.
4. Install the service with `install.sh` or register `api-private-router.service` manually.

## Database

- PostgreSQL is required for the default runtime path.
- Migrations live in `../java-backend/src/main/resources/migrations/`.
- Docker auto-setup applies packaged migrations on first startup.

## Common Commands

```bash
docker compose -f docker-compose.local.yml up -d
docker compose -f docker-compose.local.yml down
docker compose -f docker-compose.local.yml logs -f api-private-router
docker compose -f docker-compose.local.yml restart api-private-router
```

For the named-volume variant:

```bash
docker compose up -d
docker compose down
docker compose logs -f api-private-router
```

## Notes

- `AUTO_SETUP=true` is intended for Docker bootstrap and will initialize the database and admin account on first run.
- Keep `JWT_SECRET` and `TOTP_ENCRYPTION_KEY` stable across restarts if you want persistent sessions and valid existing 2FA secrets.
- The web UI is exposed on the host port configured by `SERVER_PORT`.
