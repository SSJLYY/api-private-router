#!/bin/sh
set -e

# Fix data directory permissions when running as root.
# Docker named volumes / host bind-mounts may be owned by root,
# preventing the non-root api-private-router user from writing files.
if [ "$(id -u)" = "0" ]; then
    mkdir -p /app/data
    # Use || true to avoid failure on read-only mounted files (e.g. config.yaml:ro)
    chown -R api-private-router:api-private-router /app/data 2>/dev/null || true
    # Re-invoke this script as api-private-router so the flag-detection below
    # also runs under the correct user.
    exec su-exec api-private-router "$0" "$@"
fi

# Compatibility: if the first arg looks like a flag (e.g. --help),
# prepend the default java command.
if [ "${1#-}" != "$1" ]; then
    set -- java -jar /app/api-private-router.jar "$@"
fi

exec "$@"
