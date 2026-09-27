#!/bin/sh
# Replace only the exact known Consumer bind-mounted JAR, with automatic rollback.
set -eu

release_dir=/opt/smart-assistant/releases/data-intake-20260927
live_jar=/opt/smart-assistant/releases/order-clarification-v2-20260926/smart-assistant-consumer-1.0.0-SNAPSHOT.jar
candidate_jar="$release_dir/consumer-main-e27c7592.jar"
backup_jar="$release_dir/consumer-before-main-e27c7592.jar"
expected_live_sha=86486049dcc3458dbe3f05d8b59a87420ee45a653043722670c1836a4d05699d
expected_candidate_sha=3d4611d62130f2f1e58365e8983bba0d8aa6760681151a0536ecf2e115ebbd5a

actual_mount=$(docker inspect smart-consumer --format '{{range .Mounts}}{{if eq .Destination "/app/app.jar"}}{{.Source}}{{end}}{{end}}')
test "$actual_mount" = "$live_jar"
echo "$expected_live_sha  $live_jar" | sha256sum -c -
echo "$expected_candidate_sha  $candidate_jar" | sha256sum -c -
docker exec smart-gateway sh -c 'wget -qO- -T 5 http://smart-consumer:8082/actuator/health/readiness' | grep -q '"status":"UP"'

if [ -e "$backup_jar" ]; then
    echo "$expected_live_sha  $backup_jar" | sha256sum -c -
else
    cp -p "$live_jar" "$backup_jar"
fi
stop_consumer() {
    # Podman can report a conmon exit-code error after the Java process has
    # actually stopped. Continue only when the inspected container is stopped.
    if ! docker stop -t 45 smart-consumer >/dev/null; then
        state=$(docker inspect smart-consumer --format '{{.State.Running}}')
        if [ "$state" != false ]; then
            echo "Consumer remains running after stop error: $state" >&2
            return 1
        fi
        echo 'Stop reported a runtime error, but Consumer is confirmed stopped' >&2
    fi
}
completed=0
rollback() {
    if [ "$completed" -eq 0 ]; then
        echo 'Consumer cutover failed; restoring the backed-up JAR' >&2
        state=$(docker inspect smart-consumer --format '{{.State.Running}}')
        if [ "$state" = true ]; then
            stop_consumer
        fi
        cp -p "$backup_jar" "$live_jar"
        docker start smart-consumer >/dev/null
    fi
}
trap rollback EXIT

stop_consumer
cp -p "$candidate_jar" "$live_jar"
docker start smart-consumer >/dev/null

attempt=0
while [ "$attempt" -lt 45 ]; do
    if docker exec smart-gateway sh -c 'wget -qO- -T 3 http://smart-consumer:8082/actuator/health/readiness' 2>/dev/null | grep -q '"status":"UP"'; then
        completed=1
        echo 'Consumer cutover readiness: UP'
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done
test "$completed" -eq 1
sha256sum "$live_jar" "$backup_jar"
