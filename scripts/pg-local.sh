#!/usr/bin/env bash
# 本地开发用 PostgreSQL（conda rag 环境，端口 54329）启停助手。
# 用法： bash scripts/pg-local.sh {start|stop|status|psql}
set -euo pipefail

PGBIN=/work1/mengqingbo/anaconda3/envs/rag/bin
PGDATA=/work1/mengqingbo/personal/.pg-agent-data
PGPORT=54329
LOG=/tmp/pg-agent.log

case "${1:-status}" in
  start)  "$PGBIN/pg_ctl" -D "$PGDATA" -o "-p $PGPORT -k /tmp" -l "$LOG" -w start ;;
  stop)   "$PGBIN/pg_ctl" -D "$PGDATA" -w stop ;;
  status) "$PGBIN/pg_ctl" -D "$PGDATA" status ;;
  psql)   PGPASSWORD=ecommerce_password "$PGBIN/psql" "host=127.0.0.1 port=$PGPORT dbname=ecommerce_offline user=ecommerce_user" ;;
  *)      echo "用法: bash scripts/pg-local.sh {start|stop|status|psql}"; exit 1 ;;
esac
