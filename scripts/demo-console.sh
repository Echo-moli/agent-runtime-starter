#!/usr/bin/env bash
# 启动可视化运维面板（常驻）。
#
#   bash scripts/demo-console.sh          # 默认 PostgreSQL（需先 docker compose up -d --wait）
#   AGENT_DB=h2 bash scripts/demo-console.sh   # 没装 PG 时用 H2 替身
#
# 启动后浏览器打开 http://localhost:8080/agent-console.html
# 面板调的是同源 actuator 端点，不需要 CORS、不需要 token。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mvn -B -q clean install -DskipTests -Deval.skip=true
mvn -B -q -pl agent-runtime-samples dependency:build-classpath \
    -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime

SEP=':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
esac
CP="agent-runtime-core/target/classes${SEP}agent-runtime-samples/target/classes${SEP}$(cat agent-runtime-samples/target/cp.txt)"

# 通过系统属性切换数据库方言，与其余 demo 一致。
DB_PROP=()
if [[ "${AGENT_DB:-}" == "h2" ]]; then
    DB_PROP=(-Dagent.db=h2)
fi

echo "[console] 面板地址: http://localhost:8080/agent-console.html"
echo "[console] Ctrl+C 退出"
exec java "${DB_PROP[@]}" -cp "$CP" com.agentruntime.samples.console.ConsoleDemo
