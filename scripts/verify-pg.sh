#!/usr/bin/env bash
# 真 PostgreSQL 方言一键验收：起 Docker PG → 构建 → 跑全部里程碑 demo。
#
#   本地（Git Bash / Linux / macOS）：bash scripts/verify-pg.sh
#   CI（PG 由 GitHub Actions service container 提供）：SKIP_DOCKER_UP=1 bash scripts/verify-pg.sh
#
# 与 .github/workflows/ci.yml 的 pg-dialect job 共用这一份脚本，避免本地与 CI 各写一套命令漂移。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ "${SKIP_DOCKER_UP:-0}" != "1" ]]; then
    command -v docker >/dev/null 2>&1 || { echo "[verify-pg] 未找到 docker，请先安装 Docker Desktop" >&2; exit 1; }
    # --wait 等健康检查通过；PG 没 ready 就直接跑 demo 只会报「连不上库」的误导性错误。
    docker compose up -d --wait
fi

# 单测与评测门禁由 CI 的 verify job 覆盖；这里只要 demo 的可执行产物。
mvn -B -q clean install -DskipTests -Deval.skip=true
mvn -B -q -pl agent-runtime-samples dependency:build-classpath \
    -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime

# Windows(Git Bash) 的 classpath 分隔符是分号，Linux/macOS 是冒号；Maven 生成的 cp.txt 同理。
SEP=':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
esac
CP="agent-runtime-core/target/classes${SEP}agent-runtime-samples/target/classes${SEP}$(cat agent-runtime-samples/target/cp.txt)"

run() {
    echo "===== $* ====="
    java -cp "$CP" "$@"
}

# 崩溃演示的阶段 A 故意 Runtime.halt(17)，不是失败；阶段 B 才是断言所在。
expect_halt17() {
    local main="$1" arg="$2" code
    set +e
    java -cp "$CP" "$main" "$arg"
    code=$?
    set -e
    if [[ "$code" -ne 17 ]]; then
        echo "[verify-pg] $main $arg 预期 halt(17)，实际 $code" >&2
        exit 1
    fi
}

run com.agentruntime.samples.refund.RefundAgentDemo
run com.agentruntime.samples.m4.M4Demo
run com.agentruntime.samples.eval.M5Demo
run com.agentruntime.samples.m8.M8Demo
run com.agentruntime.samples.m9.M9Demo
run com.agentruntime.samples.refund.M3Demo

expect_halt17 com.agentruntime.samples.refund.RecoverDemo crash
run com.agentruntime.samples.refund.RecoverDemo recover

expect_halt17 com.agentruntime.samples.refund.InDoubtDemo crash
run com.agentruntime.samples.refund.InDoubtDemo recover

echo "[verify-pg] 真 PostgreSQL 方言验收全部通过"
