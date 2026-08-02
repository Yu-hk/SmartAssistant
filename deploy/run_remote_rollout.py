#!/usr/bin/env python3
"""Run one of the repository's server-side rollout repair modes over SSH."""

from __future__ import annotations

import argparse
import getpass
import shlex
import sys

import paramiko


REMOTE_APP = "/opt/smart-assistant"
MARKERS = {
    "order-router": "ORDER_ROUTER_REPAIRED",
    "router-consumer": "ROUTER_CONSUMER_REPAIRED",
    "repair-services": "SERVICES_REPAIRED",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", default="root")
    parser.add_argument("--mode", choices=sorted(MARKERS), required=True)
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
        command = (
            f"cd {shlex.quote(REMOTE_APP)} && "
            f"bash deploy/remote-rollout.sh {shlex.quote(args.mode)}"
        )
        _, stdout, stderr = ssh.exec_command(command, timeout=600)
        code = stdout.channel.recv_exit_status()
        output = stdout.read().decode("utf-8", errors="replace").strip()
        error = stderr.read().decode("utf-8", errors="replace").strip()
        if code != 0 or MARKERS[args.mode] not in output:
            raise RuntimeError(error or output or f"Rollout failed: {args.mode}")
        print(output)
        return 0
    finally:
        ssh.close()


if __name__ == "__main__":
    sys.exit(main())
