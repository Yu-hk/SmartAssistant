#!/usr/bin/env python3
"""Deploy the recent-order conversation flow with recoverable JAR backups."""

from __future__ import annotations

import argparse
import getpass
import os
import posixpath
import shlex
import sys
import time

import paramiko


REMOTE_APP = "/opt/smart-assistant"
SERVICES = ("order", "router", "consumer")


def run(ssh: paramiko.SSHClient, command: str, timeout: int = 900) -> tuple[int, str, str]:
    _, stdout, stderr = ssh.exec_command(command, timeout=timeout)
    code = stdout.channel.recv_exit_status()
    return (
        code,
        stdout.read().decode("utf-8", errors="replace").strip(),
        stderr.read().decode("utf-8", errors="replace").strip(),
    )


def local_jar(workspace: str, service: str) -> str:
    module = f"smart-assistant-{service}"
    return os.path.join(workspace, module, "target", f"{module}-1.0.0-SNAPSHOT.jar")


def remote_jar(service: str) -> str:
    module = f"smart-assistant-{service}"
    return f"{REMOTE_APP}/{module}/target/{module}-1.0.0-SNAPSHOT.jar"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", default="root")
    parser.add_argument("--workspace", default=".")
    args = parser.parse_args()

    workspace = os.path.abspath(args.workspace)
    jars = {service: local_jar(workspace, service) for service in SERVICES}
    missing = [path for path in jars.values() if not os.path.isfile(path)]
    if missing:
        raise SystemExit("Missing packaged JARs: " + ", ".join(missing))

    password = getpass.getpass("SSH password: ")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    backup_dir = f"{REMOTE_APP}/backups/recent-order-flow-{timestamp}"

    try:
        ssh.connect(
            args.host,
            port=args.port,
            username=args.user,
            password=password,
            look_for_keys=False,
            allow_agent=False,
            timeout=30,
        )

        backup_commands = [f"mkdir -p {shlex.quote(backup_dir)}"]
        for service in SERVICES:
            target = remote_jar(service)
            backup_commands.append(
                f"if [ -f {shlex.quote(target)} ]; then "
                f"cp -p {shlex.quote(target)} "
                f"{shlex.quote(posixpath.join(backup_dir, service + '.jar'))}; fi"
            )
        code, output, error = run(ssh, " && ".join(backup_commands))
        if code != 0:
            raise RuntimeError(error or output or "Could not back up deployed JARs")

        with ssh.open_sftp() as sftp:
            for service in SERVICES:
                target = remote_jar(service)
                temporary = target + ".uploading"
                print(f"Uploading {service} ({os.path.getsize(jars[service]) // 1024 // 1024} MiB)...", flush=True)
                sftp.put(jars[service], temporary)
                print(f"Uploaded {service}", flush=True)

        install_commands = []
        for service in SERVICES:
            target = remote_jar(service)
            temporary = target + ".uploading"
            install_commands.append(
                f"chmod 0644 {shlex.quote(temporary)} && "
                f"mv -f {shlex.quote(temporary)} {shlex.quote(target)}"
            )
        code, output, error = run(ssh, " && ".join(install_commands))
        if code != 0:
            raise RuntimeError(error or output or "Could not install service JARs")

        for mode, marker in (
            ("order-only", "ORDER_REPAIRED"),
            ("router-consumer", "ROUTER_CONSUMER_REPAIRED"),
        ):
            code, output, error = run(
                ssh,
                f"cd {shlex.quote(REMOTE_APP)} && bash deploy/remote-rollout.sh {mode}",
            )
            if code != 0 or marker not in output:
                raise RuntimeError(error or output or f"Rollout failed: {mode}")
            print(f"Completed {mode}", flush=True)

        code, status, error = run(
            ssh,
            "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1/",
            timeout=60,
        )
        if code != 0 or status != "200":
            raise RuntimeError(error or f"Unexpected frontend status: {status}")

        print(f"Backup directory: {backup_dir}")
        print("Recent-order flow rollout completed; frontend HTTP status: 200")
        return 0
    finally:
        ssh.close()


if __name__ == "__main__":
    sys.exit(main())
