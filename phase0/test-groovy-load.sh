#!/usr/bin/env bash
# Diagnose Groovy notification plugin registration on Rundeck 5.9.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

compose() { docker compose "$@"; }

RUNDECK_URL="${RUNDECK_URL:-http://localhost:4440}"
API_VERSION="${API_VERSION:-52}"
PROJECT="${PROJECT:-phase0}"
COOKIE_JAR="${COOKIE_JAR:-$SCRIPT_DIR/.rundeck-cookies.txt}"
REPORT="${REPORT:-$SCRIPT_DIR/groovy-load-report.txt}"

log() { echo "[groovy-load] $*" | tee -a "$REPORT"; }

wait_rundeck() {
  log "Waiting for Rundeck..."
  local i=0
  while [[ $i -lt 90 ]]; do
    if compose ps rundeck 2>/dev/null | grep -q '(healthy)'; then
      if curl -sf "$RUNDECK_URL/" >/dev/null 2>&1; then
        log "Rundeck is healthy and HTTP is up."
        return 0
      fi
    fi
    sleep 5
    i=$((i + 1))
  done
  log "ERROR: Rundeck not ready."
  compose logs rundeck | tail -40 | tee -a "$REPORT"
  exit 1
}

login() {
  rm -f "$COOKIE_JAR"
  local code
  code=$(curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST \
    "${RUNDECK_URL}/j_security_check" \
    -d "j_username=${ADMIN_USER:-admin}&j_password=${ADMIN_PASS:-admin}" \
    -o /dev/null -w "%{http_code}")
  log "Login HTTP: $code"
  curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -H "Accept: application/json" \
    "${RUNDECK_URL}/api/${API_VERSION}/system/info" >/dev/null \
    || { log "ERROR: API unauthorized after login."; exit 1; }
}

api_post_yaml() {
  local path="$1"
  local yaml_file="$2"
  curl -sf -b "$COOKIE_JAR" -X POST \
    "${RUNDECK_URL}/api/${API_VERSION}${path}" \
    -H "Content-Type: application/yaml" \
    --data-binary @"$yaml_file"
}

test_plugin_import() {
  local plugin_title="$1"
  local job_name="$2"
  local yaml_file
  yaml_file=$(mktemp)
  cat >"$yaml_file" <<EOF
- defaultTab: nodes
  name: ${job_name}
  group: phase0
  executionEnabled: true
  scheduleEnabled: false
  notification:
    onsuccess:
      plugin:
        type: ${plugin_title}
        configuration: {}
  sequence:
    commands:
    - script: echo groovy load test
    keepgoing: false
    strategy: node-first
EOF
  log "--- Import job with plugin: ${plugin_title}"
  local result
  if result=$(api_post_yaml "/project/${PROJECT}/jobs/import?format=yaml&dupeOption=update" "$yaml_file" 2>&1); then
    log "RESULT: $result"
    if echo "$result" | grep -q '"succeeded":\[' && echo "$result" | grep -qv '"succeeded":\[\]'; then
      log "VERDICT: REGISTERED"
    else
      log "VERDICT: NOT REGISTERED"
    fi
  else
    log "RESULT: request failed"
    log "VERDICT: NOT REGISTERED"
  fi
  rm -f "$yaml_file"
  log ""
}

collect_logs() {
  log "--- Rundeck logs (plugin/groovy related) ---"
  docker compose logs rundeck 2>&1 | grep -iE 'Phase0|Microsoft Teams|groovy|BeanCreation|Script|plugin.*not found|Error' | tail -40 | tee -a "$REPORT" || true
  log ""
  log "--- libext groovy files ---"
  docker compose exec -T rundeck bash -lc 'ls -la /home/rundeck/libext/*.groovy' 2>&1 | tee -a "$REPORT"
}

main() {
  : > "$REPORT"
  log "=== Groovy load diagnosis $(date -Iseconds) ==="
  wait_rundeck
  login

  api_post_yaml "/projects" <(printf '{"name":"%s","description":"groovy load test"}' "$PROJECT") 2>/dev/null \
    || curl -sf -b "$COOKIE_JAR" -X POST "${RUNDECK_URL}/api/${API_VERSION}/projects" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"${PROJECT}\",\"description\":\"groovy load test\"}" >/dev/null || true

  test_plugin_import "MinimalNotificationPlugin" "groovy-minimal-load-test"
  test_plugin_import "MicrosoftTeamsNotificationWithLog" "groovy-teams-load-test"
  collect_logs
  log "=== Done. Report: $REPORT ==="
}

main "$@"
