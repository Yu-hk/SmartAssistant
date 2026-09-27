#!/usr/bin/env python3
"""Start the product-intake service alongside a mounted-JAR production release.

Copies only database/discovery variables from the running Consumer into the
Docker client's environment. Secret values are not printed or placed in argv.
Refuses to replace an existing container. Use the Compose service for normal
Compose-based installations instead.
"""

import json
import os
import pathlib
import subprocess
import sys


NAME = "smart-data-intake"
SOURCE = "smart-consumer"
ENV_KEYS = (
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR",
    "SPRING_CLOUD_NACOS_DISCOVERY_USERNAME",
    "SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD",
)


def inspect(name):
    result = subprocess.run(["docker", "inspect", name], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True)
    if result.returncode != 0:
        raise RuntimeError(f"container {name} is unavailable")
    return json.loads(result.stdout)[0]


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: start_data_intake_from_live_env.py /absolute/path/data-intake.jar")
    jar = pathlib.Path(sys.argv[1]).resolve(strict=True)
    try:
        jar.relative_to("/opt/smart-assistant/releases")
    except ValueError:
        raise SystemExit("JAR must be an existing release artifact under /opt/smart-assistant/releases")
    if not jar.is_file() or jar.suffix != ".jar":
        raise SystemExit("JAR must be an existing release artifact under /opt/smart-assistant/releases")
    existing = subprocess.run(["docker", "container", "inspect", NAME],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if existing.returncode == 0:
        raise SystemExit(f"{NAME} already exists; refusing replacement")

    source = inspect(SOURCE)
    available = dict(value.split("=", 1) for value in source["Config"]["Env"] if "=" in value)
    missing = [key for key in ENV_KEYS if not available.get(key)]
    if missing:
        raise SystemExit("source container missing required variable names: " + ", ".join(missing))
    client_env = os.environ.copy()
    client_env.update({key: available[key] for key in ENV_KEYS})
    image = source["Config"]["Image"]
    command = [
        "docker", "run", "-d", "--name", NAME,
        "--restart", "unless-stopped", "--network", "smart-network",
        "--memory", "512m",
        "--mount", f"type=bind,src={jar},dst=/app/app.jar,readonly",
    ]
    for key in ENV_KEYS:
        command.extend(["--env", key])
    command.extend([
        "--env", "OTEL_SDK_DISABLED=true",
        "--env", "PORT=8092",
        image,
        "java", "-Dfile.encoding=UTF-8", "-Xms64m", "-Xmx256m",
        "-jar", "/app/app.jar", "--server.port=8092",
    ])
    result = subprocess.run(command, env=client_env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True)
    if result.returncode:
        raise SystemExit("docker run failed: " + result.stderr.strip())
    print("Started " + NAME + " as " + result.stdout.strip()[:12])


if __name__ == "__main__":
    main()
