#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SmartAssistant - Upload & Deploy Script
Uploads JAR files, updated docker-compose.yml, and manages deployment on the server.
"""

import paramiko
import os
import sys
import time
import traceback

# Server config
HOST = "123.56.6.102"
PORT = 22
USER = "root"
PASSWORD = "Yu149286-"

# Local files to upload
LOCAL_FILES = {
    r"D:\workspace\SmartAssistant\smart-assistant-gateway\target\smart-assistant-gateway-1.0.0-SNAPSHOT.jar": "/opt/smart-assistant/jars/smart-assistant-gateway-1.0.0-SNAPSHOT.jar",
    r"D:\workspace\SmartAssistant\deploy\docker-compose.yml": "/opt/smart-assistant/docker-compose.yml",
}

REMOTE_DEPLOY_DIR = "/opt/smart-assistant"
REMOTE_JARS_DIR = REMOTE_DEPLOY_DIR + "/jars"


def upload_files(sftp):
    """Upload all necessary files to the server."""
    print("=" * 60)
    print("[Upload] Uploading files...")
    print("=" * 60)

    for dir_path in [REMOTE_DEPLOY_DIR, REMOTE_JARS_DIR]:
        try:
            sftp.stat(dir_path)
        except FileNotFoundError:
            sftp.mkdir(dir_path)
            print("  Created directory: " + dir_path)

    for local_path, remote_path in LOCAL_FILES.items():
        if os.path.exists(local_path):
            file_size = os.path.getsize(local_path)
            print("  Uploading: " + os.path.basename(local_path) + " (" + str(round(file_size / 1024 / 1024, 1)) + " MB)...")
            sftp.put(local_path, remote_path)
            print("    OK -> " + remote_path)
        else:
            print("    MISSING: " + local_path)

    print("[Upload] Complete!\n")


def run_command(ssh, command, timeout=120):
    """Run a command and return the output."""
    stdin, stdout, stderr = ssh.exec_command(command, timeout=timeout)
    exit_code = stdout.channel.recv_exit_status()
    out = stdout.read().decode('utf-8', errors='replace').strip()
    err = stderr.read().decode('utf-8', errors='replace').strip()
    return exit_code, out, err


def check_container_status(ssh):
    """Check the status of all containers."""
    print("=" * 60)
    print("[Check] Current container status...")
    print("=" * 60)

    exit_code, out, err = run_command(ssh, "podman ps -a --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'", timeout=30)
    if exit_code == 0 and out:
        header = "{:<25} {:<30} {}".format("Container", "Status", "Ports")
        print(header)
        print("-" * 90)
        for line in out.split('\n'):
            parts = line.split('\t')
            if len(parts) >= 2:
                ports = parts[2] if len(parts) > 2 else ''
                print("{:<25} {:<30} {}".format(parts[0], parts[1], ports))
    else:
        print("  No containers found.")
        if err:
            print("  Error: " + err)


def restart_nacos(ssh):
    """Restart Nacos container with updated config."""
    print("=" * 60)
    print("[Nacos] Restarting Nacos with fixed config...")
    print("=" * 60)

    # Stop and remove existing container
    print("  Stopping and removing old Nacos container...")
    run_command(ssh, "podman stop smart-nacos 2>/dev/null; podman rm smart-nacos 2>/dev/null", timeout=30)

    # Check network
    exit_code, out, err = run_command(ssh, "podman network exists smart-network && echo 'exists' || echo 'not found'", timeout=10)
    if 'not found' in out:
        print("  Creating smart-network...")
        run_command(ssh, "podman network create smart-network", timeout=15)

    # Start Nacos with correct port mapping (internal 8080 -> external 8848)
    nacos_cmd = (
        "podman run -d --name smart-nacos "
        "--network smart-network "
        "-p 8848:8080 -p 9848:9848 "
        "-e MODE=standalone "
        "-e JVM_XMS=256m -e JVM_XMX=512m "
        "-e NACOS_AUTH_ENABLE=true "
        "-e NACOS_AUTH_IDENTITY_KEY=serverIdentity "
        "-e NACOS_AUTH_IDENTITY_VALUE=security "
        "-e NACOS_AUTH_TOKEN=SecretKey012345678901234567890123456789012345678901234567890123456789 "
        "-e NACOS_AUTH_USERNAME=nacos "
        "-e NACOS_AUTH_PASSWORD=nacos123 "
        "-v nacos-data:/home/nacos/data "
        "--restart unless-stopped "
        "docker.m.daocloud.io/nacos/nacos-server:v3.1.0"
    )

    print("  Starting Nacos container...")
    exit_code, out, err = run_command(ssh, nacos_cmd, timeout=120)
    if exit_code == 0:
        container_id = out[:20] if out else 'OK'
        print("  Nacos started: " + container_id)
    else:
        print("  FAILED: " + (err or out))
        return False

    print("  Waiting for Nacos to initialize (30s)...")
    time.sleep(30)

    # Verify - Nacos v3.x uses port 8080 internally, path is /
    exit_code, out, err = run_command(ssh, "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/", timeout=15)
    print("  Nacos HTTP response: " + out)
    return exit_code == 0 or out == "200"


def start_gateway(ssh):
    """Start the Gateway service."""
    print("=" * 60)
    print("[Gateway] Starting Gateway...")
    print("=" * 60)

    # Check JAR
    exit_code, out, err = run_command(ssh, "ls -la /opt/smart-assistant/jars/smart-assistant-gateway-1.0.0-SNAPSHOT.jar", timeout=15)
    if exit_code != 0:
        print("  ERROR: Gateway JAR not found on server!")
        return False

    jar_info = out.split('\n')[0] if out else 'found'
    print("  JAR found: " + jar_info)

    # Remove existing container
    run_command(ssh, "podman stop smart-gateway 2>/dev/null; podman rm smart-gateway 2>/dev/null", timeout=15)

    # Check network
    run_command(ssh, "podman network exists smart-network 2>/dev/null || podman network create smart-network", timeout=10)

    # Start Gateway
    gateway_cmd = (
        "podman run -d --name smart-gateway "
        "--network smart-network "
        "-p 8081:8081 "
        "-e PORT=8081 "
        "-e JWT_SECRET=YourSuperSecretKeyForJWTTokenGeneration2024 "
        "-e REDIS_PASSWORD=redis123 "
        "-e NACOS_PASSWORD=nacos123 "
        "-e SPRING_DATA_REDIS_HOST=smart-redis "
        "-e SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=smart-nacos:8848 "
        "-e SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD=nacos123 "
        "-e MANAGEMENT_TRACING_ZIPKIN_TRACING_ENDPOINT=http://smart-zipkin:9411/api/v2/spans "
        "--restart unless-stopped "
        "-v /opt/smart-assistant/jars:/app/jars:ro "
        "--memory 512m "
        "eclipse-temurin:21-jre-alpine "
        "java -jar /app/jars/smart-assistant-gateway-1.0.0-SNAPSHOT.jar"
    )

    print("  Starting Gateway container...")
    exit_code, out, err = run_command(ssh, gateway_cmd, timeout=120)
    if exit_code == 0:
        container_id = out[:20] if out else 'OK'
        print("  Gateway started: " + container_id)
        return True
    else:
        print("  FAILED: " + (err or out))
        return False


def check_logs(ssh, container_name, lines=30):
    """Check container logs."""
    exit_code, out, err = run_command(ssh, "podman logs " + container_name + " --tail " + str(lines), timeout=30)
    return out, err


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    
    print("\n" + "=" * 60)
    print("SMARTASSISTANT - DEPLOYMENT SCRIPT")
    print("=" * 60 + "\n")

    print("[SSH] Connecting to " + HOST + "...")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

    try:
        ssh.connect(HOST, port=PORT, username=USER, password=PASSWORD,
                    look_for_keys=False, allow_agent=False, timeout=30)
        print("[SSH] Connected successfully!\n")

        # Step 1: Upload files
        sftp = ssh.open_sftp()
        upload_files(sftp)
        sftp.close()

        # Step 2: Check current state
        check_container_status(ssh)

        # Step 3: Restart Nacos with fixed config
        nacos_ok = restart_nacos(ssh)

        # Step 4: Start Gateway
        if nacos_ok:
            gateway_ok = start_gateway(ssh)
            
            # Step 5: Wait and check logs
            if gateway_ok:
                print("\n[Gateway] Waiting 45s for initialization...")
                time.sleep(45)
                
                print("\n[Gateway] Last 30 lines of logs:")
                print("-" * 60)
                out, err = check_logs(ssh, "smart-gateway", 30)
                if out:
                    # Print only the last relevant lines
                    lines = out.split('\n')
                    for line in lines[-30:]:
                        # Filter for important messages
                        if any(kw in line.lower() for kw in ['started', 'error', 'exception', 'nacos', 'register', 'success', 'fail', 'listening', 'port', 'health']):
                            print("  " + line)
                if err:
                    print("  [stderr] " + err)
                print("-" * 60)
        else:
            print("\n[Nacos] Not healthy, skipping Gateway start.")

        # Final check
        print("\n[Final] Container status:")
        check_container_status(ssh)

        print("\n" + "=" * 60)
        print("DONE - Script completed")
        print("=" * 60)
        print("\nCheck browser: http://" + HOST + ":8848/")
        print("Gateway check: curl http://" + HOST + ":8081/actuator/health")

    except paramiko.AuthenticationException as e:
        print("[ERROR] Authentication failed: " + str(e))
        print("  Try using FinalShell manually instead.")
    except paramiko.SSHException as e:
        print("[ERROR] SSH connection failed: " + str(e))
    except Exception as e:
        print("[ERROR] Unexpected error: " + str(e))
        traceback.print_exc()
    finally:
        ssh.close()


if __name__ == "__main__":
    main()
