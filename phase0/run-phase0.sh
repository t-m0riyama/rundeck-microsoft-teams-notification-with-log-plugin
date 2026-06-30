#!/usr/bin/env bash
# Phase 0: Reproduce plugin behavior on Rundeck 5.x (mock webhook instead of Teams).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RUNDECK_URL="${RUNDECK_URL:-http://localhost:4440}"
API_VERSION="${API_VERSION:-52}"
PROJECT="${PROJECT:-phase0}"
WEBHOOK_URL="${WEBHOOK_URL:-http://mock-webhook:8080/hook}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
COOKIE_JAR="${COOKIE_JAR:-$SCRIPT_DIR/.rundeck-cookies.txt}"
REPORT_FILE="${REPORT_FILE:-$SCRIPT_DIR/phase0-report.txt}"

log() { echo "[phase0] $*" | tee -a "$REPORT_FILE"; }

: > "$REPORT_FILE"
log "=== Phase 0 report $(date -Iseconds) ==="
log "Rundeck: $RUNDECK_URL (API v$API_VERSION)"
log "Webhook (mock): $WEBHOOK_URL"
log ""

compose() {
  docker compose "$@"
}

wait_rundeck() {
  log "Waiting for Rundeck healthcheck..."
  compose up -d --build
  local i=0
  while [[ $i -lt 60 ]]; do
    if curl -sf "$RUNDECK_URL/" >/dev/null 2>&1; then
      log "Rundeck HTTP is up."
      return 0
    fi
    sleep 5
    i=$((i + 1))
  done
  log "ERROR: Rundeck did not become ready."
  compose logs rundeck | tail -80 | tee -a "$REPORT_FILE"
  exit 1
}

api() {
  local method="$1"
  local path="$2"
  shift 2
  curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X "$method" \
    -H "Accept: application/json" \
  "${RUNDECK_URL}/api/${API_VERSION}${path}" "$@"
}

login() {
  log "--- Login (session cookie) ---"
  rm -f "$COOKIE_JAR"
  local code
  code=$(curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST \
    "${RUNDECK_URL}/j_security_check" \
    -d "j_username=${ADMIN_USER}&j_password=${ADMIN_PASS}" \
    -o /dev/null -w "%{http_code}")
  log "j_security_check HTTP: $code"
  if ! api GET /system/info >/dev/null 2>&1; then
    log "ERROR: login failed (API still unauthorized). Check admin credentials."
    exit 1
  fi
  log "Session authenticated."
  log ""
}

inspect_container() {
  log "--- Container paths & connectivity ---"
  compose exec -T rundeck bash -lc '
    set -x
    ls -la /home/rundeck/libext/ || true
    ls -la /var/lib/rundeck/libext/MicrosoftTeamsNotificationWithLog.d/ || true
    echo "--- curl HTTPS 4443 /api ---"
    curl -sk -o /dev/null -w "https://localhost:4443/api -> HTTP %{http_code}\n" https://localhost:4443/api || true
    echo "--- curl HTTP 4440 /api ---"
    curl -s -o /dev/null -w "http://localhost:4440/api -> HTTP %{http_code}\n" http://localhost:4440/api || true
  ' 2>&1 | tee -a "$REPORT_FILE"
  log ""
}

create_token() {
  log "--- API token ---"
  local resp
  resp=$(api POST /tokens \
    -H "Content-Type: application/json" \
    -d "{\"user\":\"$ADMIN_USER\",\"roles\":\"admin,user\",\"duration\":\"1d\",\"name\":\"phase0-$(date +%s)\"}")
  TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
  log "Token created (prefix): ${TOKEN:0:8}..."
  log ""
}

ensure_project() {
  log "--- Project $PROJECT ---"
  if api GET "/project/$PROJECT" >/dev/null 2>&1; then
    log "Project exists."
  else
    api POST /projects -H "Content-Type: application/json" \
      -d "{\"name\":\"$PROJECT\",\"description\":\"Phase0 test\"}" | tee -a "$REPORT_FILE"
    log "Project created."
  fi
  log ""
}

import_job() {
  log "--- Import test job ---"
  local job_yaml
  job_yaml=$(mktemp)
  cat >"$job_yaml" <<EOF
- defaultTab: nodes
  description: Phase0 Teams notification plugin test
  executionEnabled: true
  name: phase0-notification-test
  group: phase0
  notification:
    onsuccess:
      plugin:
        type: MicrosoftTeamsNotificationWithLog
        configuration:
          webhook_url: $WEBHOOK_URL
          rundeck_authtoken: $TOKEN
          include_outputlog: "true"
          template_name: SimpleMessage-AdaptiveCard
          template_language: ja
    onfailure:
      plugin:
        type: MicrosoftTeamsNotificationWithLog
        configuration:
          webhook_url: $WEBHOOK_URL
          rundeck_authtoken: $TOKEN
          include_outputlog: "true"
          template_name: SimpleMessage-AdaptiveCard
          template_language: ja
  scheduleEnabled: false
  sequence:
    commands:
    - script: |
        echo "phase0 stdout line 1"
        echo "phase0 stdout line 2"
    keepgoing: false
    strategy: node-first
EOF

  api POST "/project/$PROJECT/jobs/import?format=yaml&dupeOption=update" \
    -H "Content-Type: application/yaml" \
    --data-binary @"$job_yaml" | tee -a "$REPORT_FILE"
  rm -f "$job_yaml"
  log ""
}

run_job() {
  log "--- Run job ---"
  local job_id
  job_id=$(api GET "/project/$PROJECT/jobs?group=phase0&jobFilter=phase0-notification-test" | \
    python3 -c "import sys,json; jobs=json.load(sys.stdin); print(jobs[0]['id'] if jobs else '')")
  if [[ -z "$job_id" ]]; then
    log "ERROR: job not found"
    exit 1
  fi
  log "Job id: $job_id"
  local run_resp exec_id
  run_resp=$(api POST "/job/$job_id/run" -H "Content-Type: application/json" -d '{}')
  echo "$run_resp" | tee -a "$REPORT_FILE"
  exec_id=$(echo "$run_resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  log "Execution id: $exec_id"

  local status="" i=0
  while [[ $i -lt 30 ]]; do
    status=$(api GET "/execution/$exec_id" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
    log "Execution status: $status"
    [[ "$status" == "succeeded" || "$status" == "failed" || "$status" == "aborted" ]] && break
    sleep 3
    i=$((i + 1))
  done
  log ""
  echo "$exec_id"
}

test_plugin_api_calls() {
  local exec_id="$1"
  log "--- Simulate plugin log API calls (from inside rundeck container) ---"
  compose exec -T rundeck bash -lc "
    set -x
    echo '>> Plugin default: https://localhost:4443/api/31'
    curl -sk -H 'Accept: application/json' -H 'X-Rundeck-Auth-Token: $TOKEN' \
      'https://localhost:4443/api/31/execution/$exec_id/output' | head -c 500 || true
    echo ''
    echo '>> HTTP 4440 api/31'
    curl -s -H 'Accept: application/json' -H 'X-Rundeck-Auth-Token: $TOKEN' \
      'http://localhost:4440/api/31/execution/$exec_id/output' | head -c 500 || true
    echo ''
    echo '>> HTTP 4440 api/46'
    curl -s -H 'Accept: application/json' -H 'X-Rundeck-Auth-Token: $TOKEN' \
      'http://localhost:4440/api/46/execution/$exec_id/output' | head -c 500 || true
    echo ''
  " 2>&1 | tee -a "$REPORT_FILE"
  log ""
}

collect_logs() {
  log "--- Rundeck notification-related logs ---"
  compose logs rundeck 2>&1 | grep -iE 'notification|Teams|MicrosoftTeams|plugin|error|exception' | tail -60 | tee -a "$REPORT_FILE" || true
  log ""
  log "--- Mock webhook logs ---"
  compose logs mock-webhook 2>&1 | tail -40 | tee -a "$REPORT_FILE"
  log ""
}

main() {
  wait_rundeck
  inspect_container
  login
  create_token
  ensure_project
  import_job
  local exec_id
  exec_id=$(run_job)
  sleep 5
  test_plugin_api_calls "$exec_id"
  collect_logs
  log "=== Phase 0 finished. Full report: $REPORT_FILE ==="
}

main "$@"
