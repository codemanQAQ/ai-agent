#!/usr/bin/env bash
# 本地启动 ai-agent（JDK25 + 本地 postgres + .env.sh）。日志 -> /tmp/agent-app.log
set -uo pipefail
cd /work1/mengqingbo/personal/ai-agent

export JAVA_HOME=/work1/mengqingbo/anaconda3/envs/rag
export PATH="$JAVA_HOME/bin:$PATH"
source ./.env.sh

# 商品目录已导入 public.catalog_*（100 SPU / 585 SKU）。如需重导，取消下面注释：
# export RAG_CATALOG_DATASET_IMPORT_ENABLED=true
# export RAG_CATALOG_DATASET_IMPORT_ROOT=/work1/mengqingbo/personal/ecommerce_agent_dataset

bash scripts/pg-local.sh start >/dev/null 2>&1 || true

echo "SPRING_AI_MODEL_CHAT=${SPRING_AI_MODEL_CHAT:-<unset>} | DATASOURCE=${SPRING_DATASOURCE_URL:-<unset>}"
exec ./mvnw -q -DskipTests spring-boot:run
