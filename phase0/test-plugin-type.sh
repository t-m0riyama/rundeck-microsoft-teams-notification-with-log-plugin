#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
COOKIE_JAR=.rundeck-cookies.txt
rm -f "$COOKIE_JAR"
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST "http://localhost:4440/j_security_check" \
  -d "j_username=admin&j_password=admin" -o /dev/null

for t in \
  "Phase0 Minimal Notification" \
  "MinimalNotificationPlugin" \
  "Microsoft Teams notification with log output" \
  "MicrosoftTeamsNotificationWithLog"; do
  yaml_file=$(mktemp)
  cat >"$yaml_file" <<EOF
- name: type-test
  group: phase0
  executionEnabled: true
  scheduleEnabled: false
  notification:
    onsuccess:
      plugin:
        type: ${t}
        configuration: {}
  sequence:
    commands:
    - script: echo test
    keepgoing: false
    strategy: node-first
EOF
  result=$(curl -s -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    "http://localhost:4440/api/52/project/phase0/jobs/import?format=yaml&dupeOption=update" \
    -H "Content-Type: application/yaml" --data-binary @"$yaml_file")
  rm -f "$yaml_file"
  echo "=== type: ${t}"
  echo "$result" | python3 -m json.tool 2>/dev/null || echo "$result"
  echo
done
