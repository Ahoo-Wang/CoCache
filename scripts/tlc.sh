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

# 用 TLC 检验协议的 TLA+ 模型（docs/spec/CoCache.tla，RFC 0005 §4.10）。
#
#   scripts/tlc.sh                 运行 docs/spec 下除 Findings*.cfg 之外的全部配置（CI 的 TLC 作业）
#   scripts/tlc.sh Cache Liveness  只运行这些配置（不带 .cfg 后缀；也可以是前缀）
#   scripts/tlc.sh Findings        复现已报告的反例：全部 Findings*.cfg，预期失败（见 docs/spec/README.md）
#
# tla2tools.jar 按固定的发布版本下载到 build/tla/，校验 SHA-256 之后才使用；它是工具，不是 Gradle 依赖。
# 每个配置的输出写到 build/tla/<配置>.out。环境变量：TLC_WORKERS（默认 auto）、TLC_HEAP（默认 4g）。

set -euo pipefail

TLA_VERSION="v1.7.4"
TLA_SHA256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"
TLA_URL="https://github.com/tlaplus/tlaplus/releases/download/${TLA_VERSION}/tla2tools.jar"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEC_DIR="$ROOT/docs/spec"
OUT_DIR="$ROOT/build/tla"
JAR="$OUT_DIR/tla2tools-${TLA_VERSION}.jar"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

fetch_tools() {
  mkdir -p "$OUT_DIR"
  if [[ -f "$JAR" && "$(sha256 "$JAR")" == "$TLA_SHA256" ]]; then
    return
  fi
  local tmp
  tmp="$(mktemp "$OUT_DIR/tla2tools.XXXXXX")"
  echo "Downloading tla2tools.jar ${TLA_VERSION}"
  curl -fsSL --retry 3 -o "$tmp" "$TLA_URL"
  local actual
  actual="$(sha256 "$tmp")"
  if [[ "$actual" != "$TLA_SHA256" ]]; then
    rm -f "$tmp"
    echo "tla2tools.jar checksum mismatch: expected $TLA_SHA256, got $actual" >&2
    exit 1
  fi
  mv "$tmp" "$JAR"
}

configs=()
if [[ $# -gt 0 ]]; then
  for name in "$@"; do
    name="${name%.cfg}"
    if [[ -f "$SPEC_DIR/$name.cfg" ]]; then
      configs+=("$name")
      continue
    fi
    # 前缀：Findings 展开为全部 Findings*.cfg
    matched=0
    for cfg in "$SPEC_DIR/$name"*.cfg; do
      [[ -f "$cfg" ]] || continue
      configs+=("$(basename "$cfg" .cfg)")
      matched=1
    done
    [[ $matched -eq 1 ]] || { echo "No such config: docs/spec/$name.cfg" >&2; exit 2; }
  done
else
  for cfg in "$SPEC_DIR"/*.cfg; do
    name="$(basename "$cfg" .cfg)"
    [[ "$name" == Findings* ]] || configs+=("$name")
  done
fi

fetch_tools

failed=()
for name in "${configs[@]}"; do
  out="$OUT_DIR/$name.out"
  echo "== TLC: docs/spec/$name.cfg"
  start=$SECONDS
  # TLC 的状态目录放在 build/ 下，不留在 docs/spec 中
  if (cd "$SPEC_DIR" && java -XX:+UseParallelGC -Xmx"${TLC_HEAP:-4g}" -cp "$JAR" tlc2.TLC \
        -config "$name.cfg" -workers "${TLC_WORKERS:-auto}" -cleanup \
        -metadir "$OUT_DIR/states/$name" CoCache.tla) >"$out" 2>&1; then
    status="ok"
  else
    status="FAILED"
    failed+=("$name")
  fi
  grep -E '^[0-9,]+ states generated|^The depth' "$out" | sed 's/^/   /' || true
  echo "   $status in $((SECONDS - start)) s (output: build/tla/$name.out)"
  if [[ "$status" == "FAILED" ]]; then
    grep -E '^Error' "$out" | sed 's/^/   /' || true
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "TLC failed: ${failed[*]}" >&2
  exit 1
fi
