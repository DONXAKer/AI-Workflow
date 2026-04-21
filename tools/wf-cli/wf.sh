#!/usr/bin/env bash
set -euo pipefail

BASE="${WF_HOST:-http://host.docker.internal:8020}"
WF_USER="${WF_USER:-admin}"
WF_PASS="${WF_PASSWORD:-}"

COOKIE_JAR=$(mktemp)
trap 'rm -f "$COOKIE_JAR"' EXIT

# ── colors ────────────────────────────────────────────────────────────────────

RED='\033[31m'; GRN='\033[32m'; YLW='\033[33m'; CYN='\033[36m'
GRY='\033[90m'; BLD='\033[1m';  RST='\033[0m'

paint_status() {
  case "$1" in
    RUNNING)             echo -e "${YLW}● $1${RST}" ;;
    COMPLETED)           echo -e "${GRN}✓ $1${RST}" ;;
    FAILED)              echo -e "${RED}✗ $1${RST}" ;;
    PAUSED_FOR_APPROVAL) echo -e "${CYN}⏸ $1${RST}" ;;
    CANCELLED)           echo -e "${GRY}⊘ $1${RST}" ;;
    *)                   echo "$1" ;;
  esac
}

ago() {
  local iso="$1"
  [ -z "$iso" ] && echo "-" && return
  local now secs
  now=$(date -u +%s)
  secs=$(( now - $(date -u -d "$iso" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%S" "${iso%%.*}" +%s 2>/dev/null || echo "$now") ))
  [ $secs -lt 60 ]   && echo "${secs}s ago" && return
  [ $secs -lt 3600 ] && echo "$(( secs/60 ))m ago" && return
  echo "$(( secs/3600 ))h ago"
}

# ── auth ──────────────────────────────────────────────────────────────────────

ensure_auth() {
  [ -z "$WF_PASS" ] && echo "Set WF_PASSWORD env var for write commands." >&2 && exit 1

  # Fetch CSRF cookie
  curl -sf -c "$COOKIE_JAR" "$BASE/actuator/health" -o /dev/null || true

  # Login
  curl -sf -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$WF_USER\",\"password\":\"$WF_PASS\"}" -o /dev/null || true

  # Refresh token
  curl -sf -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE/actuator/health" -o /dev/null || true
}

csrf() {
  grep "XSRF-TOKEN" "$COOKIE_JAR" | awk '{print $NF}' | tail -1
}

wf_get() {
  curl -sf "$BASE$1"
}

wf_post() {
  ensure_auth
  local path="$1" body="${2:-{}}"
  curl -sf -X POST "$BASE$path" \
    -H "Content-Type: application/json" \
    -H "X-XSRF-TOKEN: $(csrf)" \
    -b "$COOKIE_JAR" \
    -d "$body"
}

# ── project auto-detect ───────────────────────────────────────────────────────
# Reads .ai-workflow/pipelines/feature.yaml path from WF_PROJECT_DIR env
# or falls back to /project if mounted.

PROJECT_DIR="${WF_PROJECT_DIR:-/project}"
PIPELINE_CONFIG="$PROJECT_DIR/.ai-workflow/pipelines/feature.yaml"
TASKS_ACTIVE="$PROJECT_DIR/tasks/active"

resolve_task() {
  local input="$1"
  # Already an absolute path
  [[ "$input" == /* ]] && echo "$input" && return

  # Search tasks/active by ID prefix or substring
  local match
  match=$(find "$TASKS_ACTIVE" -maxdepth 1 -name "*${input}*" -type f 2>/dev/null | head -1)
  [ -z "$match" ] && echo "No task file matching '$input' in $TASKS_ACTIVE" >&2 && exit 1
  echo "$match"
}

# ── commands ──────────────────────────────────────────────────────────────────

cmd_runs() {
  local data
  data=$(wf_get "/api/runs?page=0&size=30")

  echo ""
  printf "  %-10s  %-28s  %-10s  %s\n" "ID" "STATUS" "WHEN" "TASK"
  printf "  %s\n" "$(printf '─%.0s' {1..90})"

  echo "$data" | jq -r '
    (.content // .) | .[] |
    [.id[0:8], .status, (.startedAt // "-"), (.requirement // "-")] |
    @tsv
  ' | while IFS=$'\t' read -r id status started req; do
    req="${req: -55}"
    printf "  %-10s  %-20s  %-10s  %s\n" "$id" "$(paint_status "$status")" "$(ago "$started")" "…$req"
  done

  echo ""
  echo -e "  ${GRY}Use full UUID for status/approve/logs${RST}"
  echo ""
}

cmd_status() {
  local runId="$1"
  [ -z "$runId" ] && echo "Usage: wf status <runId>" >&2 && exit 1

  local r
  r=$(wf_get "/api/runs/$runId")

  local status req config started current
  status=$(echo "$r"  | jq -r '.status')
  req=$(echo "$r"     | jq -r '.requirement // "-"')
  config=$(echo "$r"  | jq -r '.configPath // "-"')
  started=$(echo "$r" | jq -r '.startedAt // "-"')
  current=$(echo "$r" | jq -r '.currentBlockId // ""')

  echo ""
  echo -e "  $(paint_status "$status")"
  echo "  ID:       $runId"
  echo "  Config:   $config"
  echo "  Task:     $req"
  echo "  Started:  $(ago "$started")  ($started)"
  [ -n "$current" ] && echo "  Current:  $current"

  local blocks
  blocks=$(echo "$r" | jq -r '.completedBlocks // [] | .[] | [.blockId, (.success // true | tostring)] | @tsv')
  if [ -n "$blocks" ]; then
    echo ""
    echo "  Blocks:"
    echo "$blocks" | while IFS=$'\t' read -r bid ok; do
      [ "$ok" = "true" ] && icon="${GRN}✓${RST}" || icon="${RED}✗${RST}"
      echo -e "    $icon  $bid"
    done
  fi

  if [ "$status" = "PAUSED_FOR_APPROVAL" ]; then
    echo ""
    echo -e "  ${CYN}⏸  Waiting for approval${RST}"
    echo -e "  Run: ${BLD}wf approve $runId${RST}"
  fi
  echo ""
}

cmd_approve() {
  local runId="$1" comment="${2:-}"
  [ -z "$runId" ] && echo "Usage: wf approve <runId> [comment]" >&2 && exit 1

  local body="{\"action\":\"approve\"}"
  [ -n "$comment" ] && body="{\"action\":\"approve\",\"comment\":\"$comment\"}"

  wf_post "/api/runs/$runId/approval" "$body" > /dev/null
  echo -e "  ${GRN}✓ Approved${RST}  $runId"
}

cmd_cancel() {
  local runId="$1"
  [ -z "$runId" ] && echo "Usage: wf cancel <runId>" >&2 && exit 1

  wf_post "/api/runs/$runId/cancel" > /dev/null
  echo -e "  ${YLW}⊘ Cancelled${RST}  $runId"
}

cmd_logs() {
  local runId="$1"
  [ -z "$runId" ] && echo "Usage: wf logs <runId>" >&2 && exit 1

  local r
  r=$(wf_get "/api/runs/$runId")

  echo ""
  echo "$r" | jq -r '.completedBlocks // [] | .[] | [.blockId, (.success // true | tostring), (.outputJson // "{}")] | @tsv' \
  | while IFS=$'\t' read -r bid ok out; do
    [ "$ok" = "true" ] && icon="${GRN}✓${RST}" || icon="${RED}✗${RST}"
    echo -e "  $icon ${BLD}$bid${RST}"
    echo "$out" | jq -r 'to_entries[] | "     \(.key): \(.value | tostring | .[0:200])"' 2>/dev/null || echo "     $out"
    echo ""
  done
}

cmd_run() {
  local task="$1"
  [ -z "$task" ] && echo "Usage: wf run <task-id>  (e.g. wf run MCP-CONTENT-004)" >&2 && exit 1

  local taskPath
  taskPath=$(resolve_task "$task")

  echo "  Task:    $taskPath"
  echo "  Config:  $PIPELINE_CONFIG"
  echo ""

  local resp
  resp=$(wf_post "/api/runs" \
    "{\"configPath\":\"$PIPELINE_CONFIG\",\"entryPointId\":\"implement\",\"requirement\":\"$taskPath\"}")

  local runId
  runId=$(echo "$resp" | jq -r '.runId // .id')
  echo -e "  ${GRN}✓ Run started${RST}"
  echo "  ID: $runId"
  echo ""
  echo "  Follow:  wf status $runId"
  echo ""
}

show_help() {
  cat <<EOF

  ${BLD}wf — AI-Workflow CLI${RST}

  COMMANDS
    runs                       List active and recent runs
    run   <task-id>            Start a run  (e.g. wf run MCP-CONTENT-004)
    status  <runId>            Show run status and block progress
    approve <runId> [note]     Approve a paused step
    cancel  <runId>            Cancel a run
    logs    <runId>            Show block outputs

  ENV VARS
    WF_HOST         API URL            (default: http://host.docker.internal:8020)
    WF_USER         Username           (default: admin)
    WF_PASSWORD     Password           (required for run/approve/cancel)
    WF_PROJECT_DIR  Project root       (default: /project — Docker mount)

EOF
}

# ── dispatch ──────────────────────────────────────────────────────────────────

case "${1:-help}" in
  runs)    cmd_runs ;;
  run)     cmd_run     "${2:-}" ;;
  status)  cmd_status  "${2:-}" ;;
  approve) cmd_approve "${2:-}" "${3:-}" ;;
  cancel)  cmd_cancel  "${2:-}" ;;
  logs)    cmd_logs    "${2:-}" ;;
  *)       show_help ;;
esac
