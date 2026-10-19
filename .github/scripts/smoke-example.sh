#!/usr/bin/env bash
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# 冒烟测试 cocache-example：启动给定命令（原生可执行文件，或 `java -Dspring.aot.enabled=true -jar ...`），
# 等待 HTTP 端口就绪后请求示例接口，覆盖接口式缓存、spec 缓存、组合视图（SpEL 关联 key）、Spring Cache 注解与 actuator 端点。
# 需要 localhost:6379 上的 Redis。用法：smoke-example.sh <命令> [参数...]；端口由 SMOKE_PORT 指定（默认 8008）。
set -euo pipefail

PORT="${SMOKE_PORT:-8008}"
BASE="http://localhost:$PORT"
LOG="${SMOKE_LOG:-${TMPDIR:-/tmp}/cocache-example-smoke.log}"

"$@" "--server.port=$PORT" >"$LOG" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "$BASE/actuator/cocache" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "::error::cocache-example exited before it became ready"
    cat "$LOG"
    exit 1
  fi
  sleep 1
done

# expect <描述> <期望包含的文本> <curl 参数...>
expect() {
  local description="$1" expected="$2"
  shift 2
  local body
  if ! body="$(curl -fsS "$@")" || [[ "$body" != *"$expected"* ]]; then
    echo "::error::$description - expected [$expected], got [${body:-<request failed>}]"
    cat "$LOG"
    exit 1
  fi
  echo "ok - $description: $body"
}

ID="smoke-$RANDOM-$$"
expect "interface cache (loader + Jackson codec)" "\"name\":\"user-$ID\"" "$BASE/test/$ID"
expect "spec cache (StringCodec)" "product-$ID" "$BASE/test/product/$ID"
expect "join cache (joinKeyExpression)" "\"joinKey\":\"user-of-e-$ID\"" "$BASE/test/extend?ids=e-$ID"
expect "put" "$ID" -X POST "$BASE/test/$ID"
expect "invalidate" "" -X DELETE "$BASE/test/$ID"
expect "@Cacheable(sync = true) loads through the method" "\"name\":\"first\"" "$BASE/test/spring/$ID?name=first"
expect "@Cacheable(sync = true) hits the cache" "\"name\":\"first\"" "$BASE/test/spring/$ID?name=second"
expect "actuator endpoint" "\"name\":\"UserCache\"" "$BASE/actuator/cocache"
echo "cocache-example smoke test passed"
