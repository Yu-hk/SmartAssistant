#!/usr/bin/env python3
"""Upload the built frontend to the currently running Nginx container host mount."""

from __future__ import annotations

import argparse
import getpass
import os
import posixpath
import shlex
import sys
import time

import paramiko


def run(ssh: paramiko.SSHClient, command: str) -> tuple[int, str, str]:
    _, stdout, stderr = ssh.exec_command(command, timeout=60)
    code = stdout.channel.recv_exit_status()
    return (
        code,
        stdout.read().decode("utf-8", errors="replace").strip(),
        stderr.read().decode("utf-8", errors="replace").strip(),
    )


def ensure_remote_dir(sftp: paramiko.SFTPClient, path: str) -> None:
    current = "/"
    for part in path.strip("/").split("/"):
        current = posixpath.join(current, part)
        try:
            sftp.stat(current)
        except FileNotFoundError:
            sftp.mkdir(current)


def upload_tree(sftp: paramiko.SFTPClient, local_root: str, remote_root: str) -> int:
    uploaded = 0
    for current_dir, _, filenames in os.walk(local_root):
        relative_dir = os.path.relpath(current_dir, local_root)
        remote_dir = remote_root if relative_dir == "." else posixpath.join(
            remote_root, relative_dir.replace(os.sep, "/")
        )
        ensure_remote_dir(sftp, remote_dir)
        for filename in filenames:
            local_path = os.path.join(current_dir, filename)
            remote_path = posixpath.join(remote_dir, filename)
            sftp.put(local_path, remote_path)
            uploaded += 1
    return uploaded


def discover_container_runtime(ssh: paramiko.SSHClient) -> str:
    for runtime in ("docker", "podman"):
        code, output, _ = run(
            ssh,
            f"{runtime} inspect smart-nginx --format '{{{{.Name}}}}'",
        )
        if code == 0 and output:
            return runtime
    raise RuntimeError("smart-nginx container is not available in Docker or Podman")


def discover_frontend_mount(ssh: paramiko.SSHClient, runtime: str) -> str:
    command = (
        f"{runtime} inspect smart-nginx "
        "--format '{{range .Mounts}}{{println .Source \"|\" .Destination}}{{end}}'"
    )
    code, output, error = run(ssh, command)
    if code != 0:
        raise RuntimeError(error or "smart-nginx container is not available")

    for line in output.splitlines():
        source, separator, destination = line.partition("|")
        if separator and destination.strip() == "/usr/share/nginx/html":
            return source.strip()
    raise RuntimeError("Could not locate the Nginx frontend mount")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--user", default="root")
    parser.add_argument("--local", required=True)
    args = parser.parse_args()

    local_root = os.path.abspath(args.local)
    if not os.path.isfile(os.path.join(local_root, "index.html")):
        raise SystemExit("Frontend build is missing index.html")

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
        runtime = discover_container_runtime(ssh)
        remote_root = discover_frontend_mount(ssh, runtime)
        if not remote_root.startswith("/opt/smart-assistant/") or not remote_root.endswith("/frontend/dist"):
            raise RuntimeError(f"Refusing unexpected frontend mount: {remote_root}")

        timestamp = time.strftime("%Y%m%d-%H%M%S")
        backup_root = f"/opt/smart-assistant/backups/frontend-{timestamp}"
        prepare = (
            f"mkdir -p {shlex.quote(remote_root)} {shlex.quote(backup_root)} && "
            f"cp -a {shlex.quote(remote_root + '/.')} {shlex.quote(backup_root + '/')}"
        )
        code, _, error = run(ssh, prepare)
        if code != 0:
            raise RuntimeError(error or "Could not prepare and back up frontend directory")

        with ssh.open_sftp() as sftp:
            uploaded = upload_tree(sftp, local_root, remote_root)

        code, output, error = run(
            ssh,
            f"{runtime} exec smart-nginx nginx -t && {runtime} exec smart-nginx nginx -s reload",
        )
        if code != 0:
            raise RuntimeError(error or output or "Nginx reload failed")

        code, status, error = run(
            ssh,
            "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1/",
        )
        if code != 0 or status != "200":
            raise RuntimeError(error or f"Unexpected HTTP status: {status}")

        print(f"Uploaded {uploaded} files to {remote_root}")
        print(f"Frontend backup: {backup_root}")
        print("Nginx configuration valid; frontend HTTP status: 200")
        return 0
    finally:
        ssh.close()


if __name__ == "__main__":
    sys.exit(main())
