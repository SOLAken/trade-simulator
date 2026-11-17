#!/usr/bin/env bash
# 运行撮合引擎 JMH 基准（含 GC profiler：每操作分配字节数、GC 次数）
#   ./deploy/scripts/bench.sh                 默认 G1
#   JVM_GC=-XX:+UseZGC ./deploy/scripts/bench.sh   分代 ZGC 对比
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
mvn -q -B install -pl common -am -DskipTests      # 父 POM + common 进本地仓库，build-classpath 才能解析
mvn -q -B test-compile -pl trade-service -am -DskipTests
CP_FILE=$(mktemp)
mvn -q -B dependency:build-classpath -pl trade-service -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=test
CP="trade-service/target/test-classes:trade-service/target/classes:common/target/classes:$(cat "$CP_FILE")"
rm -f "$CP_FILE"
"$JAVA_HOME/bin/java" -cp "$CP" org.openjdk.jmh.Main EngineBench -prof gc -jvmArgsAppend "${JVM_GC:--XX:+UseG1GC}" "$@"
