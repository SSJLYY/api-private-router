# api-private-router Docker Image

api-private-router provides the container runtime for the application stack in this repository.

Use the Docker Compose files under `deploy/` for the full application setup.

## Quick Start

Use this startup path:

```bash
cd deploy
cp .env.example .env
docker compose up -d
```

This starts:
- `api-private-router` Java application container
- PostgreSQL
- Redis

## Supported Docker Paths

- `deploy/docker-compose.yml`: named-volume deployment
- `deploy/docker-compose.local.yml`: local-directory deployment for easier migration/backups
- `deploy/docker-compose.standalone.yml`: app-only deployment when PostgreSQL/Redis are managed externally

## Key Environment Variables

The current Docker path uses Java/backend-specific environment variables such as:

| Variable | Description |
|----------|-------------|
| `SERVER_PORT` | Public application port |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | PostgreSQL bootstrap settings |
| `DATABASE_HOST` / `DATABASE_PORT` / `DATABASE_USER` / `DATABASE_PASSWORD` / `DATABASE_DBNAME` | Runtime database connection settings |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Runtime Redis connection settings |
| `JWT_SECRET` | Fixed JWT signing secret |
| `TOTP_ENCRYPTION_KEY` | Fixed 2FA secret encryption key |

For the complete list, see:
- `deploy/.env.example`
- `deploy/docker-compose.yml`
- `deploy/docker-compose.local.yml`

## Supported Architectures

- `linux/amd64`
- `linux/arm64`

## Tags

- `latest`: latest stable release
- `x.y.z`: specific version

## Notes

- Treat the compose files and environment templates in the local `deploy/` directory as the primary deployment reference.
- If you maintain an internal mirror or release process, update image metadata and install scripts to match that distribution path.
