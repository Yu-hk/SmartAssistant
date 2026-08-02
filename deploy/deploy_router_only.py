#!/usr/bin/env python3
"""Upload a Router JAR, keep a backup, and refresh Router dependants."""

from __future__ import annotations

import argparse
import getpass
import os
import shlex
import sys
import time

import paramiko


REMOTE_APP = "/opt/smart-assistant"
REMOTE_JAR = f"{REMOTE_APP}/smart-assistant-router/target/smart-assistant-router-1.0.0-SNAPSHOT.jar"


def run(ssh: paramiko.SSHClient, command: str, timeout: int = 300) -> tuple[int, str, str]:
    _, stdout, stderr = ssh.exec_command(command, timeout=timeout)
    code = stdout.channel.recv_exit_status()
    return (
        code,
        stdout.read().decode("utf-8", errors="replace").strip(),
        stderr.read().decode("utf-8", errors="replace").strip(),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", default="root")
    parser.add_argument("--jar", required=True)
    args = parser.parse_args()

    local_jar = os.path.abspath(args.jar)
    if not os.path.isfile(local_jar):
        raise SystemExit(f"Router JAR does not exist: {local_jar}")

    password = getpass.getpass("SSH password: ")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    remote_temp = f"{REMOTE_JAR}.uploading"
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    backup_dir = f"{REMOTE_APP}/backups/router-{timestamp}"

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

        prepare = (
            f"mkdir -p {shlex.quote(os.path.dirname(REMOTE_JAR))} {shlex.quote(backup_dir)} && "
            f"if [ -f {shlex.quote(REMOTE_JAR)} ]; then "
            f"cp -p {shlex.quote(REMOTE_JAR)} {shlex.quote(backup_dir + '/router.jar')}; fi"
        )
        code, output, error = run(ssh, prepare)
        if code != 0:
            raise RuntimeError(error or output or "Could not prepare Router deployment")

        with ssh.open_sftp() as sftp:
            sftp.put(local_jar, remote_temp)

        install = f"chmod 0644 {shlex.quote(remote_temp)} && mv -f {shlex.quote(remote_temp)} {shlex.quote(REMOTE_JAR)}"
        code, output, error = run(ssh, install)
        if code != 0:
            raise RuntimeError(error or output or "Could not install Router JAR")

        code, output, error = run(
            ssh,
            f"cd {shlex.quote(REMOTE_APP)} && bash deploy/remote-rollout.sh router-consumer",
            timeout=600,
        )
        if code != 0 or "ROUTER_CONSUMER_REPAIRED" not in output:
            raise RuntimeError(error or output or "Router/Consumer rollout failed")

        code, status, error = run(
            ssh,
            "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1/actuator/health",
            timeout=60,
        )
        if code != 0 or status not in {"200", "401", "404"}:
            raise RuntimeError(error or f"Unexpected gateway health status: {status}")

        print(f"Router backup: {backup_dir}/router.jar")
        print("Router/Consumer rollout completed; Nginx upstream refreshed")
        print(f"Gateway health HTTP status: {status}")
        return 0
    finally:
        ssh.close()


if __name__ == "__main__":
    sys.exit(main())
