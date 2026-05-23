#!/usr/bin/env bash
# W1-EVAL-02: 跑通 POST /public/agent/turn 的 SSE 流式响应。
#
# 前置：
#   1. 后端服务已起在 BASE_URL（默认 http://localhost:8080/api/v1）。
#   2. PostgreSQL + Milvus 已就绪，sample-catalog.json 已通过 import-catalog.sh 入库。
#   3. （可选）rag.rocketmq.enabled=true 时 catalog 抽属性已跑完 attributes_status=DONE。
#
# 用法：
#   scripts/rag/agent-sse-demo.sh                        # 跑全部 10 个验收用例
#   scripts/rag/agent-sse-demo.sh basic-01               # 跑指定 case id
#   BASE_URL=https://stg.example.com scripts/rag/agent-sse-demo.sh filter-03
#
# 输出：
#   - 终端实时打印 SSE 帧（event + data 行）
#   - 同时归档到 scripts/rag/.evidence/agent-sse/<caseId>-<timestamp>.log
#   - 录屏建议：
#       macOS  →  cmd+shift+5 选取终端窗口
#       Linux  →  asciinema rec --command "scripts/rag/agent-sse-demo.sh basic-01"
#       通用    →  script -q -t 0 scripts/rag/.evidence/agent-sse/<caseId>.typescript bash -c ...
#
# 退出码：
#   0 全部 case 都收到 turn.completed
#   1 任一 case 在 30s 内未收到 turn.completed 或返回非 200

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATASET="${SCRIPT_DIR}/../../src/test/resources/eval/w1-acceptance-cases.json"
EVIDENCE_DIR="${SCRIPT_DIR}/.evidence/agent-sse"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-30}"

mkdir -p "${EVIDENCE_DIR}"

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl 未安装" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq 未安装，brew install jq / apt install jq" >&2
  exit 1
fi
if [[ ! -f "${DATASET}" ]]; then
  echo "ERROR: 未找到数据集 ${DATASET}" >&2
  exit 1
fi

filter_id="${1:-}"
case_count=$(jq '.cases | length' "${DATASET}")
pass=0
fail=0

run_case() {
  local case_json="$1"
  local id query
  id=$(jq -r '.id' <<<"${case_json}")
  query=$(jq -r '.query' <<<"${case_json}")

  local ts log payload
  ts=$(date +%Y%m%d-%H%M%S)
  log="${EVIDENCE_DIR}/${id}-${ts}.log"
  payload=$(jq -n \
    --arg userId "demo-user" \
    --arg conversationId "demo-conv-${id}" \
    --arg message "${query}" \
    --arg turnId "turn-${id}-${ts}" \
    '{userId: $userId, conversationId: $conversationId, message: $message, turnId: $turnId}')

  echo "==================================================================="
  echo "▶ [${id}] ${query}"
  echo "  log: ${log}"
  echo "  ts:  ${ts}"
  echo "-------------------------------------------------------------------"

  # -N 关掉缓冲让 SSE 实时输出；--max-time 兜底防止 hang。
  if curl -N -sS -X POST "${BASE_URL}/public/agent/turn" \
      -H 'Content-Type: application/json' \
      -H 'Accept: text/event-stream' \
      --max-time "${TIMEOUT_SECONDS}" \
      -d "${payload}" \
      2>&1 | tee "${log}" | grep -E '^(event:|data:)' ; then
    if grep -q '^event: turn.completed' "${log}"; then
      echo "✅ [${id}] turn.completed 收到"
      pass=$((pass + 1))
    elif grep -q '^event: turn.error' "${log}"; then
      echo "⚠️  [${id}] turn.error 出现，详见日志"
      fail=$((fail + 1))
    else
      echo "⚠️  [${id}] 未收到 turn.completed 或 turn.error"
      fail=$((fail + 1))
    fi
  else
    echo "❌ [${id}] curl 调用失败"
    fail=$((fail + 1))
  fi
  echo
}

if [[ -n "${filter_id}" ]]; then
  case_json=$(jq -c --arg id "${filter_id}" '.cases[] | select(.id == $id)' "${DATASET}")
  if [[ -z "${case_json}" ]]; then
    echo "ERROR: case_id 不存在: ${filter_id}" >&2
    exit 1
  fi
  run_case "${case_json}"
else
  for ((i=0; i<case_count; i++)); do
    case_json=$(jq -c ".cases[$i]" "${DATASET}")
    run_case "${case_json}"
  done
fi

echo "==================================================================="
echo "汇总：通过 ${pass}，异常 ${fail}"
echo "证据已保存到 ${EVIDENCE_DIR}"
exit $([[ ${fail} -eq 0 ]] && echo 0 || echo 1)
