# Rocky Linux 9 Deployment Guide

This guide deploys `api-private-router` on a Rocky Linux 9 server with:

- Java backend as a `systemd` service
- PostgreSQL as the database
- Redis as the cache/session store
- optional Caddy reverse proxy

## 1. Server Preparation

Update the system and install basic tools:

```bash
sudo dnf update -y
sudo dnf install -y git curl wget vim tar unzip policycoreutils-python-utils
```

Open the application port if firewalld is enabled:

```bash
sudo systemctl enable --now firewalld
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

If you plan to expose the app through Caddy, also open `80/tcp` and `443/tcp`:

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
```

## 2. Install JDK 17

```bash
sudo dnf install -y java-17-openjdk java-17-openjdk-headless
java -version
```

## 3. Install PostgreSQL

Rocky 9 usually ships an older PostgreSQL module. If you want a newer upstream version, use the official PostgreSQL repository. Otherwise, install the distro package directly.

Example using distro packages:

```bash
sudo dnf install -y postgresql-server postgresql-contrib
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql
```

Create the database and user:

```bash
sudo -u postgres psql
```

Run:

```sql
CREATE USER api_private_router WITH PASSWORD 'change_this_secure_password';
CREATE DATABASE api_private_router OWNER api_private_router;
\q
```

## 4. Install Redis

```bash
sudo dnf install -y redis
sudo systemctl enable --now redis
redis-cli ping
```

If you want Redis protected with a password, edit `/etc/redis/redis.conf`, set `requirepass`, then restart Redis:

```bash
sudo systemctl restart redis
```

## 5. Obtain the Application Package

Choose one of the following:

- use your packaged release with `deploy/install.sh`
- build from source in this repository

### Option A: Use the installer script

If your release channel already serves the packaged jar/archive expected by `deploy/install.sh`, copy the script to the server and run it as root.

```bash
chmod +x deploy/install.sh
sudo bash deploy/install.sh install
```

After installation, continue with the environment and service checks below.

### Option B: Build from source

Install Maven:

```bash
sudo dnf install -y maven
```

Clone the repository and build the backend:

```bash
git clone <your-repo-url> api-private-router
cd api-private-router/java-backend
mvn clean package -DskipTests
```

Create runtime directories:

```bash
sudo useradd --system --home /opt/api-private-router --shell /sbin/nologin api-private-router || true
sudo mkdir -p /opt/api-private-router
sudo mkdir -p /etc/api-private-router
sudo cp target/*.jar /opt/api-private-router/api-private-router.jar
sudo chown -R api-private-router:api-private-router /opt/api-private-router
```

## 6. Configure Runtime Environment

The shipped `systemd` unit reads only a few built-in variables, so the simplest Rocky 9 path is to extend it with an environment file.

Create `/etc/api-private-router/api-private-router.env`:

```bash
sudo tee /etc/api-private-router/api-private-router.env >/dev/null <<'EOF'
SERVER_HOST=0.0.0.0
SERVER_PORT=8080
DATA_DIR=/opt/api-private-router
APP_BUILD_TYPE=release

DATABASE_HOST=127.0.0.1
DATABASE_PORT=5432
DATABASE_USER=api_private_router
DATABASE_PASSWORD=change_this_secure_password
DATABASE_DBNAME=api_private_router

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=

JWT_SECRET=replace_with_a_long_random_secret
TOTP_ENCRYPTION_KEY=replace_with_a_long_random_key

TZ=Asia/Shanghai
RUN_MODE=standard
EOF
```

Set strict permissions:

```bash
sudo chown root:api-private-router /etc/api-private-router/api-private-router.env
sudo chmod 640 /etc/api-private-router/api-private-router.env
```

## 7. Register the systemd Service

If you installed from source, copy the unit file:

```bash
sudo cp deploy/api-private-router.service /etc/systemd/system/api-private-router.service
```

Add environment file support with a drop-in:

```bash
sudo mkdir -p /etc/systemd/system/api-private-router.service.d
sudo tee /etc/systemd/system/api-private-router.service.d/override.conf >/dev/null <<'EOF'
[Service]
EnvironmentFile=/etc/api-private-router/api-private-router.env
EOF
```

Reload and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now api-private-router
sudo systemctl status api-private-router
```

Check logs:

```bash
sudo journalctl -u api-private-router -f
```

## 8. SELinux Notes

On Rocky 9, SELinux may block non-standard ports or outbound connections depending on policy.

If the service cannot bind to the chosen port, label it:

```bash
sudo semanage port -a -t http_port_t -p tcp 8080 || \
sudo semanage port -m -t http_port_t -p tcp 8080
```

If you use Caddy or another reverse proxy to connect to the app port, this label is usually enough.

## 9. Optional: Reverse Proxy with Caddy

Install Caddy from the official repository or your internal package source, then create a site config like:

```caddy
example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Start and enable Caddy:

```bash
sudo systemctl enable --now caddy
sudo systemctl status caddy
```

If you use the repo template, see `deploy/Caddyfile`.

## 10. Upgrade Procedure

For source deployments:

```bash
cd /path/to/api-private-router/java-backend
git pull
mvn clean package -DskipTests
sudo systemctl stop api-private-router
sudo cp target/*.jar /opt/api-private-router/api-private-router.jar
sudo chown api-private-router:api-private-router /opt/api-private-router/api-private-router.jar
sudo systemctl start api-private-router
sudo journalctl -u api-private-router -n 100 --no-pager
```

For packaged deployments, use the installer's upgrade mode if your release channel supports it:

```bash
sudo bash deploy/install.sh upgrade
```

## 11. Backup Suggestions

Back up at least:

- PostgreSQL database
- Redis data if you rely on persisted Redis state
- `/etc/api-private-router/api-private-router.env`
- `/opt/api-private-router/`

Example PostgreSQL backup:

```bash
pg_dump -U api_private_router -h 127.0.0.1 api_private_router > api-private-router.sql
```

## 12. Health Checks and Troubleshooting

Useful commands:

```bash
sudo systemctl status postgresql
sudo systemctl status redis
sudo systemctl status api-private-router
sudo journalctl -u api-private-router -n 200 --no-pager
ss -lntp | grep 8080
```

Common checks:

- verify PostgreSQL user, password, and database name
- verify Redis host and password settings
- keep `JWT_SECRET` and `TOTP_ENCRYPTION_KEY` stable after first production start
- confirm `8080/tcp` or your chosen port is open in both firewalld and SELinux policy

## 13. Recommended Production Baseline

- use HTTPS through Caddy or another reverse proxy
- use strong values for `JWT_SECRET` and `TOTP_ENCRYPTION_KEY`
- restrict PostgreSQL and Redis to local or private network access
- run regular database backups
- monitor service logs with `journalctl` or your log pipeline
