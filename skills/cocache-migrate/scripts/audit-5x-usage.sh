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

# Read-only discovery of CoCache 5.x usage in one application root.
# Every match is a review lead: verify it against the pinned 6.x release.

set -euo pipefail

usage() {
  printf 'Usage: %s <target-application-root>\n' "${0##*/}"
  printf 'Lists CoCache 5.x usage (packages, annotations, APIs, bean conventions,\n'
  printf 'properties, metrics, external invalidation protocol) as review leads.\n'
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  -*)
    printf 'ERROR: unknown option: %s\n' "$1" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ "$#" -ne 1 ]]; then
  printf 'ERROR: expected exactly one target application root.\n' >&2
  usage >&2
  exit 2
fi

scan_root="$1"

if [[ ! -d "$scan_root" ]]; then
  echo "ERROR: target is not a directory: $scan_root" >&2
  exit 2
fi

# grep -E only: works with GNU and BSD grep, no ripgrep needed. Eval fixtures (any `evals`
# directory) and dotenv files are never scanned.
common_args=(
  -r -n -o -I -E
  --exclude-dir=.git
  --exclude-dir=.idea
  --exclude-dir=.vscode
  --exclude-dir=build
  --exclude-dir=.gradle
  --exclude-dir=node_modules
  --exclude-dir=target
  --exclude-dir=evals
  --exclude='.env*'
  --exclude='*.env'
)

code_globs=(--include='*.kt' --include='*.java')
build_globs=(--include='*.gradle' --include='*.gradle.kts' --include='*.versions.toml' --include='gradle.properties' --include='pom.xml')
config_globs=(--include='*.yml' --include='*.yaml' --include='*.properties')

match_section() {
  local title="$1"
  local pattern="$2"
  shift 2

  local output
  local grep_status
  if output="$(cd "$scan_root" && LC_ALL=C command grep "${common_args[@]}" "$@" -e "$pattern" .)"; then
    :
  else
    grep_status=$?
    if [[ "$grep_status" -eq 1 ]]; then
      return 0
    fi
    printf 'ERROR: grep failed while scanning section [%s] (exit %s).\n' "$title" "$grep_status" >&2
    return "$grep_status"
  fi
  if [[ -n "$output" ]]; then
    printf '\n## %s\n%s\n' "$title" "$output"
  fi
}

printf '# CoCache 5.x to 6.x static exposure audit\n'
printf 'root: %s\n' "$(cd "$scan_root" && pwd)"
printf 'note: matches are review leads, not proof of incompatibility or completeness.\n'

match_section \
  'Declared CoCache coordinates and versions' \
  'me\.ahoo\.cocache:[[:alnum:]_.-]+(:[0-9][[:alnum:].+_-]*)?|<artifactId>cocache-[[:alnum:]_.-]+</artifactId>|<cocache[._-]?[Vv]ersion>[^<]+</cocache[._-]?[Vv]ersion>|cocache[._-]?[Vv]ersion[[:space:]]*[=:][[:space:]]*"?[0-9][[:alnum:].+_-]*"?' \
  "${build_globs[@]}"

match_section \
  'Removed 5.x modules' \
  'cocache-spring-redis|cocache-spring-cache' \
  "${build_globs[@]}"

match_section \
  'Platform and Redis client' \
  'id\("org\.springframework\.boot"\)[[:space:]]*version[[:space:]]*"[^"]+"|spring-boot-starter-parent|<spring-boot\.version>[^<]+</spring-boot\.version>|redis\.clients:jedis|<artifactId>jedis</artifactId>|io\.lettuce:lettuce-core|jvmToolchain\([0-9]+\)' \
  "${build_globs[@]}"

match_section \
  '5.x packages' \
  'me\.ahoo\.cache\.[[:alnum:]_.]+' \
  "${code_globs[@]}"

match_section \
  'Renamed or removed annotation attributes' \
  '\b(ttlAmplitude|missingTtl|clientSideTtl|refreshAhead|expireUnit)\b|TtlAt\.FOREVER' \
  "${code_globs[@]}"

match_section \
  '5.x data source and cache API' \
  '\b(CacheSource|CacheValue|PresentValue|MissingValue|TtlAt)\b|\bloadCacheValues?\b|\b(getCache|getCaches|getTtlAt|setCache)[[:space:]]*\(|\.evict[[:space:]]*\(' \
  "${code_globs[@]}"

match_section \
  'Bean-name conventions and pluggable components' \
  '"[^"]*\.(CacheSource|KeyConverter|ClientSideCache|DistributedCache|JoinKeyExtractor)"|\b(ClientSideCache|DistributedCache|MapClientSideCache|InMemoryDistributedCache|KeyConverter|ToStringKeyConverter|BloomKeyFilter|CacheEvictedEventBus|ClientIdGenerator|CoherentCacheFactory|CacheFactory|[A-Za-z]*CodecExecutor)\b' \
  "${code_globs[@]}"

match_section \
  'SetElementsCache (Redis data copy required)' \
  '\b(SetElementsCache|SetElementsStore)\b|\b(addElements|removeElements)\b' \
  "${code_globs[@]}"

match_section \
  'JoinCache and Spring Cache' \
  '\bCoCacheManager\b|@JoinCacheable|\bJoinCache\b|@Cacheable|@CachePut|@CacheEvict' \
  "${code_globs[@]}"

match_section \
  'Removed or renamed properties' \
  'cocache\.redis\.(strict-failure|eviction-channel|missing-guard-sentinel)|cocache\.load\.[[:alnum:]-]+|cocache\.metrics\.client-side-hits|cocache\.next\.[[:alnum:].-]+|^[[:space:]]*(strict-failure|eviction-channel|missing-guard-sentinel|client-side-hits|max-concurrent|acquire-timeout)[[:space:]]*:' \
  "${config_globs[@]}" "${code_globs[@]}"

match_section \
  'Actuator exposure and 5.x metric names' \
  'cocacheClient|cocache[._]l2[._]size|cocache[._]evictions[._]received|cocache[._]eviction[._]publish[._]failures|level[[:space:]]*[=:][[:space:]]*"?l2' \
  "${config_globs[@]}" "${code_globs[@]}" --include='*.json'

match_section \
  'Direct Redis access and the 5.x invalidation message' \
  '@@|\bconvertAndSend[[:space:]]*\(|\.publish[[:space:]]*\(|\bPUBLISH\b|\bHGET(ALL)?\b|\bSMEMBERS\b' \
  "${code_globs[@]}" --include='*.py' --include='*.go' --include='*.js' --include='*.ts' --include='*.sh' --include='*.lua'

printf '\nAudit complete. Confirm resolved dependencies, Redis contents, external programs and the pinned 6.x release separately.\n'
