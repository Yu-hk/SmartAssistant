#!/usr/bin/env python3
"""Upload a Consumer JAR, keep a backup, and restart the Consumer safely."""

from __future__ import annotations

import argparse
import getpass
import os
import shlex
import sys
import time
from typing import BinaryIO

import paramiko


REMOTE_APP = "/opt/smart-assistant"
REMOTE_JAR = f"{REMOTE_APP}/smart-assistant-consumer/target/smart-assistant-consumer-1.0.0-SNAPSHOT.jar"


def run(ssh: paramiko.SSHClient, command: str, timeout: int = 300) -> tuple[int, str, str]:
    _, stdout, stderr = ssh.exec_command(command, timeout=timeout)
    code = stdout.channel.recv_exit_status()
    return (
        code,
        stdout.read().decode("utf-8", errors="replace").strip(),
        stderr.read().decode("utf-8", errors="replace").strip(),
    )


def connect(host: str, port: int, user: str, password: str) -> paramiko.SSHClient:
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(
        host,
        port=port,
        username=user,
        password=password,
        look_for_keys=False,
        allow_agent=False,
        timeout=30,
    )
    transport = ssh.get_transport()
    if transport is not None:
        transport.set_keepalive(15)
    return ssh


def copy_remaining(source: BinaryIO, target: paramiko.SFTPFile) -> None:
    while chunk := source.read(1024 * 1024):
        target.write(chunk)
        target.flush()


def upload_with_resume(
    ssh: paramiko.SSHClient,
    local_path: str,
    remote_path: str,
    reconnect,
    max_attempts: int = 8,
) -> paramiko.SSHClient:
    local_size = os.path.getsize(local_path)
    last_error: Exception | None = None

    for attempt in range(1, max_attempts + 1):
        try:
            with ssh.open_sftp() as sftp:
                try:
                    remote_size = sftp.stat(remote_path).st_size
                except FileNotFoundError:
                    remote_size = 0
                if remote_size > local_size:
                    sftp.remove(remote_path)
                    remote_size = 0
                if remote_size == local_size:
                    return ssh

                print(
                    f"Upload attempt {attempt}: resuming at "
                    f"{remote_size}/{local_size} bytes"
                )
                with open(local_path, "rb") as source:
                    source.seek(remote_size)
                    with sftp.open(remote_path, "ab") as target:
                        copy_remaining(source, target)
                if sftp.stat(remote_path).st_size != local_size:
                    raise RuntimeError("Remote upload size does not match local JAR")
                return ssh
        except (EOFError, OSError, paramiko.SSHException) as error:
            last_error = error
            ssh.close()
            if attempt == max_attempts:
                break
            time.sleep(2)
            ssh = reconnect()

    raise RuntimeError(f"Consumer upload failed after {max_attempts} attempts") from last_error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", default="root")
    parser.add_argument("--jar", required=True)
    args = parser.parse_args()

    local_jar = os.path.abspath(args.jar)
    if not os.path.isfile(local_jar):
        raise SystemExit(f"Consumer JAR does not exist: {local_jar}")

    password = getpass.getpass("SSH password: ")
    ssh = connect(args.host, args.port, args.user, password)
    remote_temp = f"{REMOTE_JAR}.uploading"
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    backup_dir = f"{REMOTE_APP}/backups/consumer-{timestamp}"

    try:
        prepare = (
            f"mkdir -p {shlex.quote(os.path.dirname(REMOTE_JAR))} {shlex.quote(backup_dir)} && "
            f"if [ -f {shlex.quote(REMOTE_JAR)} ]; then "
            f"cp -p {shlex.quote(REMOTE_JAR)} {shlex.quote(backup_dir + '/consumer.jar')}; fi"
        )
        code, output, error = run(ssh, prepare)
        if code != 0:
            raise RuntimeError(error or output or "Could not prepare Consumer deployment")

        ssh = upload_with_resume(
            ssh,
            local_jar,
            remote_temp,
            lambda: connect(args.host, args.port, args.user, password),
        )

        install = (
            f"chmod 0644 {shlex.quote(remote_temp)} && "
            f"mv -f {shlex.quote(remote_temp)} {shlex.quote(REMOTE_JAR)}"
        )
        code, output, error = run(ssh, install)
        if code != 0:
            raise RuntimeError(error or output or "Could not install Consumer JAR")

        code, output, error = run(
            ssh,
            f"cd {shlex.quote(REMOTE_APP)} && bash deploy/remote-rollout.sh consumer-only",
            timeout=600,
        )
        if code != 0 or "CONSUMER_REPAIRED" not in output:
            raise RuntimeError(error or output or "Consumer rollout failed")

        code, status, error = run(
            ssh,
            "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1/actuator/health",
            timeout=60,
        )
        if code != 0 or status not in {"200", "401", "404"}:
            raise RuntimeError(error or f"Unexpected gateway health status: {status}")

        print(f"Consumer backup: {backup_dir}/consumer.jar")
        print("Consumer rollout completed; Nginx upstream refreshed")
        print(f"Gateway health HTTP status: {status}")
        return 0
    finally:
        ssh.close()


if __name__ == "__main__":
    sys.exit(main())
