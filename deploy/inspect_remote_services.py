#!/usr/bin/env python3
"""Read container state and recent logs for the deployed request path."""

from __future__ import annotations

import argparse
import getpass
import sys

import paramiko


COMMANDS = (
    (
        "containers",
        "docker ps -a --filter name=smart-order --filter name=smart-router "
        "--filter name=smart-consumer --filter name=smart-nginx "
        "--format '{{.Names}}\\t{{.Status}}\\t{{.Ports}}'",
    ),
    ("order", "docker logs --tail 120 smart-order 2>&1"),
    ("router", "docker logs --tail 120 smart-router 2>&1"),
    ("consumer", "docker logs --tail 160 smart-consumer 2>&1"),
    ("nginx", "docker logs --tail 60 smart-nginx 2>&1"),
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", default="root")
    args = parser.parse_args()

    password = getpass.getpass("SSH password: ")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
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
        for label, command in COMMANDS:
            _, stdout, _ = ssh.exec_command(command, timeout=90)
            code = stdout.channel.recv_exit_status()
            output = stdout.read().decode("utf-8", errors="replace").strip()
            print(f"\n--- {label} (exit={code}) ---")
            print(output)
        return 0
    finally:
        ssh.close()


if __name__ == "__main__":
    sys.exit(main())
