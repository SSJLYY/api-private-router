#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

FILE_RULES: dict[str, dict[str, list[tuple[str, str]]]] = {
    "Makefile": {
        "required": [
            (r"java-backend", "root Makefile must point at java-backend"),
        ],
        "forbidden": [
            (r"cd\s+backend\b", "root Makefile must not enter the old backend/ path"),
            (r"make\s+-C\s+backend\b", "root Makefile must not delegate to the old backend/ path"),
        ],
    },
    "Dockerfile": {
        "required": [
            (r"java-backend", "root Dockerfile must build from java-backend"),
            (r"api-private-router\.jar", "root Dockerfile must package the Java JAR"),
        ],
        "forbidden": [
            (r"COPY\s+backend/", "root Dockerfile must not copy the old backend/ path into the runtime path"),
        ],
    },
    "Dockerfile.release": {
        "required": [
            (r"java-backend", "release Dockerfile must build from java-backend"),
            (r"api-private-router\.jar", "release Dockerfile must package the Java JAR"),
        ],
        "forbidden": [
            (r"COPY\s+backend/", "release Dockerfile must not copy the old backend/ path into the runtime path"),
        ],
    },
    "deploy/Dockerfile": {
        "required": [
            (r"java-backend", "deploy Dockerfile must build from java-backend"),
            (r"api-private-router\.jar", "deploy Dockerfile must package the Java JAR"),
        ],
        "forbidden": [
            (r"COPY\s+backend/", "deploy Dockerfile must not copy the old backend/ path into the runtime path"),
        ],
    },
    "deploy/Makefile": {
        "required": [
            (r"java-backend", "deploy Makefile must point at java-backend"),
        ],
        "forbidden": [
            (r"cd\s+\.\./backend\b", "deploy Makefile must not enter ../backend"),
        ],
    },
    "deploy/build_image.sh": {
        "required": [
            (r"Dockerfile", "deploy image builder must use the Java-first Dockerfiles"),
        ],
        "forbidden": [
            (r"backend/", "deploy image builder must not reference backend/ as a default runtime path"),
        ],
    },
    "deploy/docker-entrypoint.sh": {
        "required": [
            (r"api-private-router\.jar", "docker entrypoint must execute the Java JAR"),
        ],
        "forbidden": [
            (r"/backend/", "docker entrypoint must not reference backend/"),
        ],
    },
    "deploy/install.sh": {
        "required": [
            (r"api-private-router\.jar", "install script must install the Java release artifact"),
        ],
        "forbidden": [
            (r"/backend/", "install script must not reference backend/ as a runtime path"),
        ],
    },
    "deploy/api-private-router.service": {
        "required": [
            (r"api-private-router\.jar", "systemd service must start the Java JAR"),
        ],
        "forbidden": [
            (r"/backend/", "systemd service must not point at backend/"),
        ],
    },
    ".github/workflows/backend-ci.yml": {
        "required": [
            (r"java-backend", "backend CI must exercise java-backend"),
        ],
        "forbidden": [
            (r"working-directory:\s*backend\b", "backend CI must not cd into backend/"),
        ],
    },
    ".github/workflows/release.yml": {
        "required": [
            (r"java-backend", "release workflow must package java-backend"),
            (r"api-private-router\.jar", "release workflow must ship the Java JAR"),
        ],
        "forbidden": [
            (r"working-directory:\s*backend\b", "release workflow must not cd into backend/"),
        ],
    },
    ".github/workflows/security-scan.yml": {
        "required": [
            (r"java-backend", "security workflow must scan java-backend"),
        ],
        "forbidden": [
            (r"working-directory:\s*backend\b", "security workflow must not cd into backend/"),
        ],
    },
}


def main() -> int:
    failures: list[str] = []
    for relative_path, rules in FILE_RULES.items():
        path = ROOT / relative_path
        if not path.exists():
            failures.append(f"{relative_path}: file is missing")
            continue

        text = path.read_text(encoding="utf-8")
        for pattern, message in rules.get("required", []):
            if re.search(pattern, text, flags=re.MULTILINE) is None:
                failures.append(f"{relative_path}: missing required pattern `{pattern}` ({message})")

        for pattern, message in rules.get("forbidden", []):
            if re.search(pattern, text, flags=re.MULTILINE):
                failures.append(f"{relative_path}: matched forbidden pattern `{pattern}` ({message})")

    if failures:
        print("Java default runtime guard failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("Java default runtime guard passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
