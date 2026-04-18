#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RUNS="${RUNS:-5}"
RESULT_LIMIT="${RESULT_LIMIT:-20}"

if [[ $# -gt 0 ]]; then
  QUERIES=("$@")
else
  QUERIES=(
    "rocketmq async indexing"
    "content sha256 version"
    "heading path filter"
  )
fi

PSQL_BASE=(psql -X -v ON_ERROR_STOP=1)
if [[ -n "${DATABASE_URL:-}" ]]; then
  PSQL_BASE+=("$DATABASE_URL")
fi

extract_execution_time() {
  awk -F': ' '/Execution Time/ {gsub(/ ms/,"",$2); print $2; exit}'
}

print_summary() {
  local label="$1"
  shift
  printf '%s\n' "$@" | awk -v label="$label" '
    BEGIN { min = -1; max = -1; sum = 0; count = 0; }
    NF {
      value = $1 + 0;
      if (min < 0 || value < min) min = value;
      if (max < 0 || value > max) max = value;
      sum += value;
      count += 1;
    }
    END {
      if (count == 0) {
        printf "%-18s avg=n/a min=n/a max=n/a runs=0\n", label;
      } else {
        printf "%-18s avg=%.3f ms min=%.3f ms max=%.3f ms runs=%d\n", label, sum / count, min, max, count;
      }
    }'
}

run_variant() {
  local label="$1"
  local sql_file="$2"
  local query="$3"
  local -a times=()

  for ((run = 1; run <= RUNS; run++)); do
    local output
    output="$("${PSQL_BASE[@]}" -v search_text="$query" -v result_limit="$RESULT_LIMIT" -f "$sql_file")"
    local execution_time
    execution_time="$(printf '%s\n' "$output" | extract_execution_time)"
    if [[ -z "$execution_time" ]]; then
      printf '未能从 %s 提取 Execution Time，完整输出如下：\n%s\n' "$sql_file" "$output" >&2
      exit 1
    fi
    times+=("$execution_time")
  done

  print_summary "$label" "${times[@]}"
}

printf 'RAG PostgreSQL 检索性能对比\n'
printf 'runs=%s result_limit=%s\n\n' "$RUNS" "$RESULT_LIMIT"

for query in "${QUERIES[@]}"; do
  printf 'Query: %s\n' "$query"
  run_variant "legacy-like" "$SCRIPT_DIR/explain_keyword_retrieval_legacy_like.sql" "$query"
  run_variant "current-fts" "$SCRIPT_DIR/explain_keyword_retrieval_current.sql" "$query"
  printf '\n'
done
