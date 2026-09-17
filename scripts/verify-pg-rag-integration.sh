#!/usr/bin/env bash
# Disposable PG only: never use docker-compose-infra.yml / smart-postgres / a2a_system.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"
PROJECT="smartassistant-pg-it-$$"
COMPOSE_FILE="$PROJECT_ROOT/deploy/docker-compose.integration.yml"
cleanup() { docker compose -p "$PROJECT" -f "$COMPOSE_FILE" down -v; }
trap cleanup EXIT
docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d --wait --wait-timeout 90 postgres
export PG_TEST_URL="jdbc:postgresql://127.0.0.1:15433/smartassistant_integration"
export PG_TEST_USER=integration
export PG_TEST_PASSWORD=integration-test-only
"${MAVEN_EXE:-mvn}" -B -pl smart-assistant-common clean test \
  -Dtest=PgIntegrationSettingsTest,PgVectorKnowledgeBaseIntegrationTest -Dpg.integration=true
"${PYTHON_EXE:-python3}" scripts/verify_integration_reports.py \
  smart-assistant-common/target/surefire-reports/TEST-com.example.smartassistant.common.rag.PgVectorKnowledgeBaseIntegrationTest.xml --minimum 7
