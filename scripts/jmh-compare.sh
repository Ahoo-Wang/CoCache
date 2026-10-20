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

# 对比当前工作区与基线 ref 的 JMH 结果，输出可直接贴进 PR 的 Markdown 表格。
#
# 方法（固化自多次手工对比的经验）：
#   - 基线在临时 git worktree 中构建，两侧各自打 jmh jar，直接 `java -jar` 运行（不经 Gradle，无需 --rerun）。
#   - 每次只跑一个基准（精确正则 `^.*\.<name>$`）：同一 JVM 任务里靠后的基准在 8 线程下会慢 20–30%。
#   - 每轮内两侧交替，先后顺序逐轮轮换；按同轮配对比值（new/base）判断，抵消随轮次的漂移。
#   - 每次开跑前等待 1 分钟负载低于门槛；按迭代 CV 超限或负载升高超过"自身贡献 + 余量"作废并重试。
#   - 用锁保证本机同一时间只有一个基准任务（构建也在锁内，避免干扰别人的运行）；不经本脚本的 JMH 进程也会等它结束。
#
# 需要 localhost:6379 上的 Redis（Redis 基准）、JDK 17+ 与 python3（只用标准库，以 -S 运行）。
# 用法见 `scripts/jmh-compare.sh --help`。

# 用 sh / zsh 显式调用时语法不兼容，先拦下来。
if [ -z "${BASH_VERSION:-}" ]; then
    echo "jmh-compare.sh 需要 bash：请直接执行 scripts/jmh-compare.sh 或用 bash 调用" >&2
    exit 2
fi
set -euo pipefail

# 整个脚本包在 { … } 里，bash 先解析完再执行：运行期间切换分支或修改本文件不会打乱正在运行的实例。
{

usage() {
    cat <<'EOF'
用法：scripts/jmh-compare.sh -b <基准[,基准...]> [选项]

  -b, --bench LIST          基准方法名，逗号分隔（必填）；可写 Class.method 以消除歧义，如 l2Hit,l2HitMixed
  -B, --base REF            基线 ref（默认 origin/main，不会自动 git fetch）；新侧固定为当前工作区（含未提交改动）
  -t, --threads LIST        线程数，逗号分隔（默认 1,8）
  -r, --rounds N            轮数（默认 5）
  -l, --load-threshold X    1 分钟负载门槛（默认 max(2, CPU 核数 × 0.4)）
  -m, --module PATH         Gradle 模块（默认 :cocache-redis）
  -o, --out DIR             输出目录（默认 build/jmh-compare/<时间戳>；非空目录需加 --force）
  -p, --param K=V           传给 JMH 的 @Param 取值，可重复
      --max-cv PCT          迭代 CV 上限，超过即作废（默认 3）
      --rise-margin X       负载升高容忍 = min(线程数, 核数) + X（默认 1.5）
      --max-retries N       每次运行作废后的最多重试次数（默认 2）
      --load-wait-max SEC   等待负载降到门槛的最长时间，超时后照常运行并在表中标注（默认 600）
      --foreign-wait-max SEC 等待不经本脚本的 JMH 进程结束的最长累计时间，超时报错退出（默认 3600）
      --forks N             JMH fork 数（默认 1）
      --warmup-iterations N 预热迭代数（默认 3）
      --warmup-time T       每次预热时长（默认 3s）
      --iterations N        测量迭代数（默认 5）
      --time T              每次测量时长（默认 5s）
      --force               允许写入非空输出目录
  -h, --help                显示本帮助

环境变量：COCACHE_JMH_LOCK 覆盖锁路径（默认 /tmp/cocache-jmh.lock，全机共享）；JAVA_HOME 指定 JDK。
EOF
}

log() { printf '[jmh-compare %s] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die() { log "错误：$*"; exit 2; }

# ---------- 参数 ----------
base_ref="origin/main"
benches=""
threads_list="1,8"
rounds=5
load_threshold=""
module=":cocache-redis"
out_dir=""
max_cv=3
rise_margin=1.5
max_retries=2
load_wait_max=600
foreign_wait_max=3600
forks=1
warmup_iterations=3
warmup_time="3s"
iterations=5
measure_time="5s"
force=0
jmh_params=()

need_arg() { [ "$#" -ge 2 ] && [ -n "${2}" ] || die "选项 ${1} 需要参数"; }

while [ "$#" -gt 0 ]; do
    case "${1}" in
        -b|--bench) need_arg "$@"; benches="${2}"; shift 2 ;;
        -B|--base) need_arg "$@"; base_ref="${2}"; shift 2 ;;
        -t|--threads) need_arg "$@"; threads_list="${2}"; shift 2 ;;
        -r|--rounds) need_arg "$@"; rounds="${2}"; shift 2 ;;
        -l|--load-threshold) need_arg "$@"; load_threshold="${2}"; shift 2 ;;
        -m|--module) need_arg "$@"; module="${2}"; shift 2 ;;
        -o|--out) need_arg "$@"; out_dir="${2}"; shift 2 ;;
        -p|--param) need_arg "$@"; jmh_params+=("-p" "${2}"); shift 2 ;;
        --max-cv) need_arg "$@"; max_cv="${2}"; shift 2 ;;
        --rise-margin) need_arg "$@"; rise_margin="${2}"; shift 2 ;;
        --max-retries) need_arg "$@"; max_retries="${2}"; shift 2 ;;
        --load-wait-max) need_arg "$@"; load_wait_max="${2}"; shift 2 ;;
        --foreign-wait-max) need_arg "$@"; foreign_wait_max="${2}"; shift 2 ;;
        --forks) need_arg "$@"; forks="${2}"; shift 2 ;;
        --warmup-iterations) need_arg "$@"; warmup_iterations="${2}"; shift 2 ;;
        --warmup-time) need_arg "$@"; warmup_time="${2}"; shift 2 ;;
        --iterations) need_arg "$@"; iterations="${2}"; shift 2 ;;
        --time) need_arg "$@"; measure_time="${2}"; shift 2 ;;
        --force) force=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "未知参数：${1}" ;;
    esac
done

is_uint() { case "${1}" in ''|*[!0-9]*) return 1 ;; esac; }
is_num() { [[ "${1}" =~ ^[0-9]+([.][0-9]+)?$ ]]; }

[ -n "${benches}" ] || { usage >&2; die "缺少 -b/--bench"; }
for n in "${rounds}" "${max_retries}" "${load_wait_max}" "${foreign_wait_max}" "${forks}" "${warmup_iterations}" "${iterations}"; do
    is_uint "${n}" || die "需要非负整数：${n}"
done
[ "${rounds}" -ge 1 ] || die "轮数至少为 1"
[ "${forks}" -ge 1 ] || die "fork 数至少为 1"
for x in "${max_cv}" "${rise_margin}" ${load_threshold:+"${load_threshold}"}; do
    is_num "${x}" || die "需要非负数：${x}"
done
command -v python3 >/dev/null 2>&1 || die "需要 python3"
command -v git >/dev/null 2>&1 || die "需要 git"

if [ -n "${JAVA_HOME:-}" ]; then java_bin="${JAVA_HOME}/bin/java"; else java_bin="java"; fi
command -v "${java_bin}" >/dev/null 2>&1 || die "找不到 java：${java_bin}"

IFS=',' read -r -a bench_arr <<<"${benches}"
IFS=',' read -r -a thread_arr <<<"${threads_list}"
for b in "${bench_arr[@]}"; do
    # 只接受 Java 标识符字符与点，其余正则元字符一律拒绝；点和 $ 在生成正则时转义。
    case "${b}" in ''|*[!A-Za-z0-9_.\$]*) die "基准名只能包含字母、数字、_、. 和 \$：${b}" ;; esac
done
for t in "${thread_arr[@]}"; do
    case "${t}" in ''|*[!0-9]*|0) die "线程数需为正整数：${t}" ;; esac
done

repo_root="$(git rev-parse --show-toplevel)"
module_dir="${module#:}"
module_dir="${module_dir//://}"
[ -d "${repo_root}/${module_dir}" ] || die "模块目录不存在：${repo_root}/${module_dir}"

ncpu="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)"
if [ -z "${load_threshold}" ]; then
    load_threshold="$(awk -v n="${ncpu}" 'BEGIN { x = n * 0.4; if (x < 2) x = 2; printf "%.1f", x }')"
fi

if [ -z "${out_dir}" ]; then
    out_dir="${repo_root}/build/jmh-compare/$(date '+%Y%m%d-%H%M%S')"
fi
if [ -d "${out_dir}" ] && [ -n "$(ls -A "${out_dir}" 2>/dev/null)" ] && [ "${force}" -ne 1 ]; then
    die "输出目录非空：${out_dir}（换一个目录，或加 --force）"
fi
mkdir -p "${out_dir}/raw" "${out_dir}/logs"
out_dir="$(cd "${out_dir}" && pwd)"
runs_tsv="${out_dir}/runs.tsv"

tmp_root="${TMPDIR:-/tmp}"
tmp_root="${tmp_root%/}"
# macOS 的 TMPDIR 按用户隔离，锁默认放在全机共享的 /tmp。
lock_path="${COCACHE_JMH_LOCK:-/tmp/cocache-jmh.lock}"

# ---------- 清理 ----------
base_worktree=""
lock_owned=0
child_pid=""

cleanup() {
    local rc=$?
    if [ -n "${child_pid}" ] && kill -0 "${child_pid}" 2>/dev/null; then
        pkill -TERM -P "${child_pid}" 2>/dev/null || true
        kill -TERM "${child_pid}" 2>/dev/null || true
        wait "${child_pid}" 2>/dev/null || true
    fi
    if [ -n "${base_worktree}" ] && [ -d "${base_worktree}" ]; then
        git -C "${repo_root}" worktree remove --force "${base_worktree}" >/dev/null 2>&1 || rm -rf "${base_worktree}"
        git -C "${repo_root}" worktree prune >/dev/null 2>&1 || true
    fi
    # 只删自己持有的锁：万一锁已被别人接管，不能误删。
    if [ "${lock_owned}" -eq 1 ] && [ "$(sed -n 1p "${lock_path}/owner" 2>/dev/null || true)" = "$$" ]; then
        rm -rf "${lock_path}"
    fi
    exit "${rc}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------- 工具函数 ----------

# 在后台运行命令并等待：收到 TERM/INT 时 wait 立即返回，cleanup 负责结束子进程。
# 用法：run_child <日志文件> <命令...>
run_child() {
    local logf="${1}" rc=0
    shift
    "$@" >"${logf}" 2>&1 &
    child_pid=$!
    wait "${child_pid}" || rc=$?
    child_pid=""
    return "${rc}"
}

# 可被信号及时打断的 sleep。
nap() {
    sleep "${1}" &
    wait $! || true
}

# 当前 1 分钟负载（Linux 读 /proc/loadavg，macOS 读 sysctl vm.loadavg）。
load1() {
    if [ -r /proc/loadavg ]; then
        cut -d' ' -f1 /proc/loadavg
    else
        sysctl -n vm.loadavg | awk '{ print $2 }'
    fi
}

# 浮点比较：fgt a b 当 a > b 时返回 0。
fgt() { awk -v a="${1}" -v b="${2}" 'BEGIN { exit !(a > b) }'; }

# 进程启动时间，与 PID 一起识别 PID 复用。
proc_start() { ps -o lstart= -p "${1}" 2>/dev/null | sed 's/^ *//; s/ *$//' || true; }

# 判断锁目录是否失效（返回 0 表示失效）。
lock_is_stale() {
    local dir="${1}" pid start cur
    if [ ! -s "${dir}/owner" ]; then
        # 进程可能死在 mkdir 与写 owner 之间：超过 1 分钟仍没有 owner 视为失效。
        [ -n "$(find "${dir}" -maxdepth 0 -mmin +1 2>/dev/null)" ]
        return
    fi
    pid="$(sed -n 1p "${dir}/owner" 2>/dev/null || true)"
    start="$(sed -n 2p "${dir}/owner" 2>/dev/null || true)"
    is_uint "${pid}" || return 0
    # 用 ps 判断存活而不是 kill -0：其他用户的进程 kill -0 返回 EPERM，但它仍持有锁。
    ps -p "${pid}" >/dev/null 2>&1 || return 0
    cur="$(proc_start "${pid}")"
    # PID 仍存活但启动时间不同：PID 已被复用，原持有者已退出。
    [ -n "${start}" ] && [ -n "${cur}" ] && [ "${start}" != "${cur}" ]
}

# 原子接管失效锁：先 rename 走再核对，避免两个等待者先后接管时删掉对方刚建好的有效锁。
take_over_stale_lock() {
    local snap moved="${lock_path}.stale.$$"
    snap="$(cat "${lock_path}/owner" 2>/dev/null || true)"
    lock_is_stale "${lock_path}" || return 1
    mv "${lock_path}" "${moved}" 2>/dev/null || return 1
    if [ "$(cat "${moved}/owner" 2>/dev/null || true)" = "${snap}" ] && lock_is_stale "${moved}"; then
        rm -rf "${moved}"
        log "清理失效锁（持有者：$(printf '%s' "${snap:-无 owner}" | tr '\n' ' ')）"
        return 0
    fi
    # 搬走的是别人刚建好的有效锁：放回原处。
    if [ ! -e "${lock_path}" ] && mv "${moved}" "${lock_path}" 2>/dev/null; then
        return 1
    fi
    log "警告：误搬走了有效锁且无法放回，保留在 ${moved}"
    return 1
}

# 用 mkdir 的原子性实现全机互斥锁。
acquire_lock() {
    local waited=0 holder
    while ! mkdir "${lock_path}" 2>/dev/null; do
        take_over_stale_lock && continue
        if [ $((waited % 60)) -eq 0 ]; then
            holder="$(tr '\n' ' ' 2>/dev/null <"${lock_path}/owner" || true)"
            log "本机已有基准任务在运行，等待锁 ${lock_path}（持有者：${holder:-未知}）"
        fi
        nap 5
        waited=$((waited + 5))
    done
    lock_owned=1
    # 先写临时文件再 rename，等待者不会读到写了一半的 owner。
    printf '%s\n%s\n%s %s\n' "$$" "$(proc_start "$$")" "$(date '+%Y-%m-%dT%H:%M:%S')" "${out_dir}" >"${lock_path}/owner.tmp"
    mv "${lock_path}/owner.tmp" "${lock_path}/owner"
    log "已获取锁 ${lock_path}"
}

# 清理上次异常退出遗留的基线 worktree（持锁后调用，此时不会有别的实例在用）。
prune_stale_base_worktrees() {
    local wt
    git -C "${repo_root}" worktree list --porcelain | sed -n 's/^worktree //p' | while IFS= read -r wt; do
        case "${wt##*/}" in
            cocache-jmh-base.*)
                log "清理遗留的基线 worktree：${wt}"
                git -C "${repo_root}" worktree remove --force "${wt}" >/dev/null 2>&1 || rm -rf "${wt}"
                ;;
        esac
    done
    git -C "${repo_root}" worktree prune >/dev/null 2>&1 || true
}

# 等待 1 分钟负载降到门槛以下；超时则返回 1（调用方照常运行并标注）。
wait_for_load() {
    local waited=0 cur
    cur="$(load1)"
    while fgt "${cur}" "${load_threshold}"; do
        if [ "${waited}" -ge "${load_wait_max}" ]; then
            log "负载 ${cur} 在 ${load_wait_max}s 内未降到门槛 ${load_threshold}，照常运行"
            return 1
        fi
        if [ $((waited % 30)) -eq 0 ]; then
            log "负载 ${cur} > 门槛 ${load_threshold}，等待…"
        fi
        nap 5
        waited=$((waited + 5))
        cur="$(load1)"
    done
    return 0
}

# 等待不经本脚本启动的 JMH 进程结束（它们不受本脚本的锁约束，同时运行会互相污染）。
# 累计等待超过 --foreign-wait-max 即报错退出。
foreign_waited=0
wait_for_foreign_jmh() {
    local announced=0
    while pgrep -f 'org\.openjdk\.jmh\.runner\.ForkedMain|-jmh\.jar' >/dev/null 2>&1; do
        [ "${foreign_waited}" -lt "${foreign_wait_max}" ] \
            || die "其他 JMH 进程累计等待超过 ${foreign_wait_max}s，放弃"
        if [ "${announced}" -eq 0 ]; then
            log "本机另有 JMH 进程在运行（未经本脚本），等待其结束…"
            announced=1
        fi
        nap 5
        foreign_waited=$((foreign_waited + 5))
    done
}

# 构建一侧的 jmh jar，并把 jar 与 logback 配置复制到输出目录（之后基线 worktree 即可删除）。
build_side() {
    local side="${1}" src="${2}" jar
    log "构建 ${side} 侧 jmh jar（${src}）"
    run_child "${out_dir}/logs/build-${side}.log" "${src}/gradlew" -p "${src}" -q --console=plain "${module}:jmhJar" \
        || die "${side} 侧构建失败，见 ${out_dir}/logs/build-${side}.log"
    jar="$(ls -t "${src}/${module_dir}"/build/libs/*-jmh.jar 2>/dev/null | head -n 1 || true)"
    [ -n "${jar}" ] || die "${side} 侧找不到 *-jmh.jar"
    mkdir -p "${out_dir}/${side}"
    cp "${jar}" "${out_dir}/${side}/benchmarks.jar"
    if [ -f "${src}/config/logback-jmh.xml" ]; then
        cp "${src}/config/logback-jmh.xml" "${out_dir}/${side}/logback-jmh.xml"
    fi
}

# 基准名 → 精确匹配的 JMH 正则（点与 $ 转义）。
bench_regex() { printf '^.*\\.%s$' "$(printf '%s' "${1}" | sed 's/[.$]/\\&/g')"; }

# 统计 jar 中匹配正则的基准数；列出失败时返回非 0。
count_matches() {
    local listing
    listing="$("${java_bin}" -jar "${out_dir}/${1}/benchmarks.jar" -l "${2}" 2>&1)" || {
        printf '%s\n' "${listing}" >&2
        return 1
    }
    printf '%s\n' "${listing}" | awk 'f && NF { c++ } /^Benchmarks:/ { f = 1 } END { print c + 0 }'
}

# 汇总与 CV 计算（只用 python 标准库）。
py() {
    python3 -S - "$@" <<'PY'
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def label(entry):
    name = ".".join(entry["benchmark"].split(".")[-2:])
    params = entry.get("params") or {}
    if params:
        name += " (" + ", ".join("%s=%s" % (k, params[k]) for k in sorted(params)) + ")"
    return name


def cv(entry):
    raw = [x for fork in entry["primaryMetric"].get("rawData", []) for x in fork]
    if len(raw) < 2:
        return 0.0
    mean = sum(raw) / len(raw)
    if mean == 0:
        return 0.0
    var = sum((x - mean) ** 2 for x in raw) / (len(raw) - 1)
    return var ** 0.5 / abs(mean) * 100


def median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def fmt(x):
    if abs(x) >= 10000:
        return "{:,.0f}".format(x)
    if abs(x) >= 100:
        return "%.1f" % x
    if abs(x) >= 1:
        return "%.3f" % x
    return "%.4g" % x


def spread(xs, f):
    if not xs:
        return "—"
    return "%s [%s–%s]" % (f(median(xs)), f(min(xs)), f(max(xs)))


def pct(x):
    return "%.1f%%" % (x * 100)


def cmd_cv(path):
    data = load(path)
    if not data:
        sys.exit(3)
    print("%.2f" % max(cv(e) for e in data))


def cmd_summary(runs_tsv, out_md, *meta):
    info = dict(m.split("=", 1) for m in meta)
    rows = []
    with open(runs_tsv, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            rows.append(dict(zip(header, line.rstrip("\n").split("\t"))))

    # (label, threads) -> {round -> {side: (score, gated)}}，只取最终有效的运行。
    groups = {}
    units = {}
    modes = set()
    for r in rows:
        if r["status"] != "ok":
            continue
        for e in load(r["json"]):
            key = (label(e), int(r["threads"]))
            units[key] = e["primaryMetric"].get("scoreUnit", "")
            modes.add(e.get("mode", ""))
            score = e["primaryMetric"]["score"]
            groups.setdefault(key, {}).setdefault(int(r["round"]), {})[r["side"]] = (score, r["gated"] == "1")

    out = []
    out.append(
        "### JMH 对比：`%s` (%s，未自动 fetch) → 工作区 (%s)" % (info["base_ref"], info["base_sha"], info["new_sha"])
    )
    out.append("")
    out.append(
        "方法：`scripts/jmh-compare.sh`；每次只跑一个基准，同轮两侧交替且先后逐轮轮换，比值为同轮配对 new/base。"
        "JMH：%s。开跑前等待 1 分钟负载 ≤ %s（%s 核，最多等 %ss）；迭代 CV > %s%% 或负载升高 > min(线程数, 核数) + %s 的运行作废并重试（最多 %s 次）。"
        % (info["jmh"], info["threshold"], info["ncpu"], info["load_wait"], info["max_cv"], info["margin"], info["retries"])
    )
    out.append("")
    if modes == {"thrpt"}:
        out.append("比值大于 100% 表示新版本更快（吞吐模式，数值越大越好）。")
    else:
        out.append("比值大于 100% 表示新侧数值更大：吞吐模式（thrpt）下即更快，耗时模式（avgt/sample/ss）下即更慢。")
    out.append("")
    out.append("| 基准 | 线程 | new/base 中位数 [min–max] | base 中位数 [min–max] | new 中位数 [min–max] | 有效配对 | 单位 |")
    out.append("|------|-----:|---------------------------|-----------------------|----------------------|---------:|------|")
    for key in sorted(groups, key=lambda k: (k[0], k[1])):
        by_round = groups[key]
        # 只用两侧都有效的轮次，两侧中位数与比值出自同一组配对。
        paired = [v for v in by_round.values() if "base" in v and "new" in v and v["base"][0]]
        base = [v["base"][0] for v in paired]
        new = [v["new"][0] for v in paired]
        ratios = [v["new"][0] / v["base"][0] for v in paired]
        gated = sum(1 for v in paired if v["base"][1] or v["new"][1])
        count = "%d/%s" % (len(ratios), info["rounds"])
        if gated:
            count += "（%d 超门槛）" % gated
        out.append(
            "| %s | %d | %s | %s | %s | %s | %s |"
            % (key[0], key[1], spread(ratios, pct), spread(base, fmt), spread(new, fmt), count, units[key])
        )
    if not groups:
        out.append("| （没有有效运行） | | | | | | |")
    if any(r["gated"] == "1" for r in rows):
        out.append("")
        out.append("“超门槛”：等待 %ss 后负载仍高于门槛、照常运行的配对轮次，结论需谨慎。" % info["load_wait"])
    if info.get("skipped"):
        out.append("")
        out.append("跳过（基线或新侧没有该基准，或匹配不唯一）：%s" % info["skipped"])

    out.append("")
    out.append("<details><summary>逐轮负载与作废记录</summary>")
    out.append("")
    out.append("| 基准 | 线程 | 轮 | 侧 | 尝试 | 开始负载 | 结束负载 | 最大 CV | 结果 |")
    out.append("|------|-----:|---:|----|-----:|---------:|---------:|--------:|------|")
    for r in rows:
        result = "有效" if r["status"] == "ok" else "作废：%s" % r["reason"]
        if r["gated"] == "1":
            result += "（开跑时负载超门槛）"
        cv_text = (r["cv"] + "%") if r["cv"] not in ("", "-") else "—"
        out.append(
            "| %s | %s | %s | %s | %s | %s | %s | %s | %s |"
            % (r["bench"], r["threads"], r["round"], r["side"], r["attempt"], r["load_start"], r["load_end"], cv_text, result)
        )
    out.append("")
    out.append("</details>")
    text = "\n".join(out) + "\n"
    with open(out_md, "w", encoding="utf-8") as f:
        f.write(text)
    sys.stdout.write(text)


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "cv":
        cmd_cv(sys.argv[2])
    elif mode == "summary":
        cmd_summary(*sys.argv[2:])
    else:
        sys.exit("unknown mode: %s" % mode)
PY
}

# 运行一侧的一次基准；作废则重试，直到有效或用完重试次数。
run_one() {
    local bench="${1}" threads="${2}" round="${3}" side="${4}"
    local regex attempt=0 status reason ls le cv rc gated json logf self
    local logback="${out_dir}/${side}/logback-jmh.xml"
    local jvm_args=()
    regex="$(bench_regex "${bench}")"
    [ -f "${logback}" ] && jvm_args=("-jvmArgsAppend" "-Dlogback.configurationFile=${logback}")
    self="$(awk -v t="${threads}" -v n="${ncpu}" -v m="${rise_margin}" 'BEGIN { print (t < n ? t : n) + m }')"
    while :; do
        attempt=$((attempt + 1))
        gated=0
        wait_for_foreign_jmh
        wait_for_load || gated=1
        json="${out_dir}/raw/${bench}-t${threads}-r${round}-${side}-a${attempt}.json"
        logf="${out_dir}/logs/${bench}-t${threads}-r${round}-${side}-a${attempt}.log"
        ls="$(load1)"
        log "${bench} t=${threads} 第 ${round}/${rounds} 轮 ${side} 侧（尝试 ${attempt}，负载 ${ls}）"
        rc=0
        run_child "${logf}" "${java_bin}" -jar "${out_dir}/${side}/benchmarks.jar" "${regex}" \
            -f "${forks}" -wi "${warmup_iterations}" -w "${warmup_time}" -i "${iterations}" -r "${measure_time}" \
            -t "${threads}" -foe true -rf json -rff "${json}" ${jvm_args[@]+"${jvm_args[@]}"} ${jmh_params[@]+"${jmh_params[@]}"} \
            || rc=$?
        # 不经本脚本启动的 JMH（持有 JMH 自己的锁）也会干扰测量：等它结束，不消耗重试次数，但计入外部等待上限。
        if [ "${rc}" -ne 0 ] && grep -q "Unable to acquire the JMH lock" "${logf}" 2>/dev/null; then
            [ "${foreign_waited}" -lt "${foreign_wait_max}" ] \
                || die "其他 JMH 实例累计等待超过 ${foreign_wait_max}s，放弃"
            log "  → 本机另有 JMH 实例在运行（未经本脚本），30s 后重试"
            rm -f "${json}" "${logf}"
            attempt=$((attempt - 1))
            nap 30
            foreign_waited=$((foreign_waited + 30))
            continue
        fi
        le="$(load1)"
        status="ok"
        reason=""
        cv="-"
        if [ "${rc}" -ne 0 ] || [ ! -s "${json}" ]; then
            status="failed"
            reason="退出码 ${rc}，见 logs/$(basename "${logf}")"
        elif ! cv="$(py cv "${json}")"; then
            status="failed"
            cv="-"
            reason="结果为空"
        elif fgt "${cv}" "${max_cv}"; then
            status="invalid"
            reason="CV ${cv}% > ${max_cv}%"
        elif fgt "$(awk -v a="${le}" -v b="${ls}" 'BEGIN { print a - b }')" "${self}"; then
            status="invalid"
            reason="负载升高 ${ls}→${le} > ${self}"
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "${bench}" "${threads}" "${round}" "${side}" "${attempt}" "${status}" "${reason}" \
            "${ls}" "${le}" "${cv}" "${gated}" "${json}" >>"${runs_tsv}"
        if [ "${status}" = "ok" ]; then
            log "  → 有效（CV ${cv}%，负载 ${ls}→${le}）"
            return 0
        fi
        log "  → 作废：${reason}"
        if [ "${attempt}" -gt "${max_retries}" ]; then
            log "  → 已达重试上限，本轮 ${side} 侧不计入配对"
            return 0
        fi
    done
}

# ---------- 主流程 ----------
base_sha="$(git -C "${repo_root}" rev-parse --short "${base_ref}^{commit}")" || die "无法解析基线 ref：${base_ref}"
new_sha="$(git -C "${repo_root}" describe --always --dirty --abbrev=7)"
log "基线 ${base_ref} (${base_sha}，未自动 fetch)，新侧为工作区 (${new_sha})；输出目录 ${out_dir}"
log "基准 ${benches}；线程 ${threads_list}；${rounds} 轮；负载门槛 ${load_threshold}（${ncpu} 核）"

acquire_lock
prune_stale_base_worktrees
wait_for_foreign_jmh
wait_for_load || true

base_worktree="$(mktemp -d "${tmp_root}/cocache-jmh-base.XXXXXX")"
rmdir "${base_worktree}"
git -C "${repo_root}" worktree add --detach "${base_worktree}" "${base_sha}" >/dev/null 2>&1 \
    || die "无法为 ${base_ref} 创建 worktree"
build_side base "${base_worktree}"
git -C "${repo_root}" worktree remove --force "${base_worktree}" >/dev/null 2>&1 || rm -rf "${base_worktree}"
git -C "${repo_root}" worktree prune >/dev/null 2>&1 || true
base_worktree=""
build_side new "${repo_root}"

run_benches=()
skipped=""
for bench in "${bench_arr[@]}"; do
    regex="$(bench_regex "${bench}")"
    nb="$(count_matches base "${regex}")" || die "列出基线侧基准失败（${regex}）"
    nn="$(count_matches new "${regex}")" || die "列出新侧基准失败（${regex}）"
    if [ "${nb}" -eq 1 ] && [ "${nn}" -eq 1 ]; then
        run_benches+=("${bench}")
    else
        log "跳过 ${bench}：基线匹配 ${nb} 个、新侧匹配 ${nn} 个（需各恰好 1 个）"
        skipped="${skipped:+${skipped}, }${bench}"
    fi
done
[ "${#run_benches[@]}" -gt 0 ] || die "没有可对比的基准"

printf 'bench\tthreads\tround\tside\tattempt\tstatus\treason\tload_start\tload_end\tcv\tgated\tjson\n' >"${runs_tsv}"
for bench in "${run_benches[@]}"; do
    for threads in "${thread_arr[@]}"; do
        for ((round = 1; round <= rounds; round++)); do
            # 先后顺序逐轮轮换，抵消"第二个跑的更热/更冷"的位置偏差。
            if [ $((round % 2)) -eq 1 ]; then order="base new"; else order="new base"; fi
            for side in ${order}; do
                run_one "${bench}" "${threads}" "${round}" "${side}"
            done
        done
    done
done

py summary "${runs_tsv}" "${out_dir}/summary.md" \
    "base_ref=${base_ref}" "base_sha=${base_sha}" "new_sha=${new_sha}" \
    "threshold=${load_threshold}" "ncpu=${ncpu}" "load_wait=${load_wait_max}" "max_cv=${max_cv}" "margin=${rise_margin}" \
    "retries=${max_retries}" "rounds=${rounds}" "skipped=${skipped}" \
    "jmh=fork ${forks}，预热 ${warmup_iterations}×${warmup_time}，测量 ${iterations}×${measure_time}"
log "完成：${out_dir}/summary.md（原始 JSON 在 ${out_dir}/raw）"
exit 0
}
