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
set -euo pipefail

usage() {
    cat <<'EOF'
用法：scripts/jmh-compare.sh -b <基准[,基准...]> [选项]

  -b, --bench LIST          基准方法名，逗号分隔（必填）；可写 Class.method 以消除歧义，如 l2Hit,l2HitMixed
  -B, --base REF            基线 ref（默认 origin/main）；新侧固定为当前工作区（含未提交改动）
  -t, --threads LIST        线程数，逗号分隔（默认 1,8）
  -r, --rounds N            轮数（默认 5）
  -l, --load-threshold X    1 分钟负载门槛（默认 max(2, CPU 核数 × 0.4)）
  -m, --module PATH         Gradle 模块（默认 :cocache-redis）
  -o, --out DIR             输出目录（默认 build/jmh-compare/<时间戳>）
  -p, --param K=V           传给 JMH 的 @Param 取值，可重复
      --max-cv PCT          迭代 CV 上限，超过即作废（默认 3）
      --rise-margin X       负载升高容忍 = min(线程数, 核数) + X（默认 1.5）
      --max-retries N       每次运行作废后的最多重试次数（默认 2）
      --load-wait-max SEC   等待负载降到门槛的最长时间，超时后照常运行并在表中标注（默认 600）
      --forks N             JMH fork 数（默认 1）
      --warmup-iterations N 预热迭代数（默认 3）
      --warmup-time T       每次预热时长（默认 3s）
      --iterations N        测量迭代数（默认 5）
      --time T              每次测量时长（默认 5s）
  -h, --help                显示本帮助

环境变量：COCACHE_JMH_LOCK 覆盖锁路径（默认 ${TMPDIR:-/tmp}/cocache-jmh.lock）；JAVA_HOME 指定 JDK。
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
forks=1
warmup_iterations=3
warmup_time="3s"
iterations=5
measure_time="5s"
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
        --forks) need_arg "$@"; forks="${2}"; shift 2 ;;
        --warmup-iterations) need_arg "$@"; warmup_iterations="${2}"; shift 2 ;;
        --warmup-time) need_arg "$@"; warmup_time="${2}"; shift 2 ;;
        --iterations) need_arg "$@"; iterations="${2}"; shift 2 ;;
        --time) need_arg "$@"; measure_time="${2}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "未知参数：${1}" ;;
    esac
done

[ -n "${benches}" ] || { usage >&2; die "缺少 -b/--bench"; }
for n in "${rounds}" "${max_retries}" "${load_wait_max}" "${forks}" "${warmup_iterations}" "${iterations}"; do
    case "${n}" in ''|*[!0-9]*) die "需要非负整数：${n}" ;; esac
done
[ "${rounds}" -ge 1 ] || die "轮数至少为 1"
command -v python3 >/dev/null 2>&1 || die "需要 python3"
command -v git >/dev/null 2>&1 || die "需要 git"

if [ -n "${JAVA_HOME:-}" ]; then java_bin="${JAVA_HOME}/bin/java"; else java_bin="java"; fi
command -v "${java_bin}" >/dev/null 2>&1 || die "找不到 java：${java_bin}"

IFS=',' read -r -a bench_arr <<<"${benches}"
IFS=',' read -r -a thread_arr <<<"${threads_list}"
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
mkdir -p "${out_dir}/raw" "${out_dir}/logs"
out_dir="$(cd "${out_dir}" && pwd)"
runs_tsv="${out_dir}/runs.tsv"

tmp_root="${TMPDIR:-/tmp}"
tmp_root="${tmp_root%/}"
lock_path="${COCACHE_JMH_LOCK:-${tmp_root}/cocache-jmh.lock}"

# ---------- 清理 ----------
base_worktree=""
lock_owned=0

cleanup() {
    local rc=$?
    if [ -n "${base_worktree}" ] && [ -d "${base_worktree}" ]; then
        git -C "${repo_root}" worktree remove --force "${base_worktree}" >/dev/null 2>&1 || rm -rf "${base_worktree}"
        git -C "${repo_root}" worktree prune >/dev/null 2>&1 || true
    fi
    if [ "${lock_owned}" -eq 1 ]; then
        rm -rf "${lock_path}"
    fi
    exit "${rc}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------- 工具函数 ----------

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

# 用 mkdir 的原子性实现本机互斥锁；持锁进程已退出时视为陈旧锁并接管。
acquire_lock() {
    local waited=0 holder
    while ! mkdir "${lock_path}" 2>/dev/null; do
        holder="$(cat "${lock_path}/owner" 2>/dev/null || true)"
        local pid="${holder%% *}"
        if [ -n "${pid}" ] && ! kill -0 "${pid}" 2>/dev/null; then
            log "清理陈旧锁（持有者 pid ${pid} 已退出）"
            rm -rf "${lock_path}"
            continue
        fi
        if [ $((waited % 60)) -eq 0 ]; then
            log "本机已有基准任务在运行，等待锁 ${lock_path}（持有者：${holder:-未知}）"
        fi
        sleep 5
        waited=$((waited + 5))
    done
    lock_owned=1
    printf '%s %s %s\n' "$$" "$(date '+%Y-%m-%dT%H:%M:%S')" "${out_dir}" >"${lock_path}/owner"
    log "已获取锁 ${lock_path}"
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
        sleep 5
        waited=$((waited + 5))
        cur="$(load1)"
    done
    return 0
}

# 等待不经本脚本启动的 JMH 进程结束（它们不受本脚本的锁约束，同时运行会互相污染）。
wait_for_foreign_jmh() {
    local waited=0
    while pgrep -f 'org\.openjdk\.jmh\.runner\.ForkedMain|-jmh\.jar' >/dev/null 2>&1; do
        if [ $((waited % 60)) -eq 0 ]; then
            log "本机另有 JMH 进程在运行（未经本脚本），等待其结束…"
        fi
        sleep 5
        waited=$((waited + 5))
    done
}

# 构建一侧的 jmh jar，并把 jar 与 logback 配置复制到输出目录（之后基线 worktree 即可删除）。
build_side() {
    local side="${1}" src="${2}" jar
    log "构建 ${side} 侧 jmh jar（${src}）"
    (cd "${src}" && ./gradlew -q --console=plain "${module}:jmhJar") >"${out_dir}/logs/build-${side}.log" 2>&1 \
        || die "${side} 侧构建失败，见 ${out_dir}/logs/build-${side}.log"
    jar="$(ls -t "${src}/${module_dir}"/build/libs/*-jmh.jar 2>/dev/null | head -n 1 || true)"
    [ -n "${jar}" ] || die "${side} 侧找不到 *-jmh.jar"
    mkdir -p "${out_dir}/${side}"
    cp "${jar}" "${out_dir}/${side}/benchmarks.jar"
    if [ -f "${src}/config/logback-jmh.xml" ]; then
        cp "${src}/config/logback-jmh.xml" "${out_dir}/${side}/logback-jmh.xml"
    fi
}

# 统计 jar 中匹配正则的基准数。
count_matches() {
    "${java_bin}" -jar "${out_dir}/${1}/benchmarks.jar" -l "${2}" 2>/dev/null \
        | awk 'f && NF { c++ } /^Benchmarks:/ { f = 1 } END { print c + 0 }'
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

    # (label, threads) -> {round -> {side: score}}，只取最终有效的运行。
    groups = {}
    units = {}
    for r in rows:
        if r["status"] != "ok":
            continue
        for e in load(r["json"]):
            key = (label(e), int(r["threads"]))
            units[key] = e["primaryMetric"].get("scoreUnit", "")
            groups.setdefault(key, {}).setdefault(int(r["round"]), {})[r["side"]] = e["primaryMetric"]["score"]

    out = []
    out.append("### JMH 对比：`%s` (%s) → 工作区 (%s)" % (info["base_ref"], info["base_sha"], info["new_sha"]))
    out.append("")
    out.append(
        "方法：`scripts/jmh-compare.sh`；每次只跑一个基准，同轮两侧交替且先后逐轮轮换，比值为同轮配对 new/base。"
        "JMH：%s。开跑前等待 1 分钟负载 ≤ %s（%s 核）；迭代 CV > %s%% 或负载升高 > min(线程数, 核数) + %s 的运行作废并重试（最多 %s 次）。"
        % (info["jmh"], info["threshold"], info["ncpu"], info["max_cv"], info["margin"], info["retries"])
    )
    out.append("")
    out.append("| 基准 | 线程 | new/base 中位数 [min–max] | base 中位数 [min–max] | new 中位数 [min–max] | 有效配对 | 单位 |")
    out.append("|------|-----:|---------------------------|-----------------------|----------------------|---------:|------|")
    for key in sorted(groups, key=lambda k: (k[0], k[1])):
        by_round = groups[key]
        base = [v["base"] for v in by_round.values() if "base" in v]
        new = [v["new"] for v in by_round.values() if "new" in v]
        ratios = [v["new"] / v["base"] for v in by_round.values() if "base" in v and "new" in v and v["base"]]
        out.append(
            "| %s | %d | %s | %s | %s | %d/%s | %s |"
            % (key[0], key[1], spread(ratios, pct), spread(base, fmt), spread(new, fmt), len(ratios), info["rounds"], units[key])
        )
    if not groups:
        out.append("| （没有有效运行） | | | | | | |")
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
        if r["status"] == "ok" and r["reason"]:
            result += "（%s）" % r["reason"]
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
    local regex="^.*\\.${bench}\$" attempt=0 status reason ls le cv rc gate json logf self
    local logback="${out_dir}/${side}/logback-jmh.xml"
    local jvm_args=()
    [ -f "${logback}" ] && jvm_args=("-jvmArgsAppend" "-Dlogback.configurationFile=${logback}")
    self="$(awk -v t="${threads}" -v n="${ncpu}" -v m="${rise_margin}" 'BEGIN { print (t < n ? t : n) + m }')"
    while :; do
        attempt=$((attempt + 1))
        gate=""
        wait_for_foreign_jmh
        wait_for_load || gate="开跑时负载超门槛"
        json="${out_dir}/raw/${bench}-t${threads}-r${round}-${side}-a${attempt}.json"
        logf="${out_dir}/logs/${bench}-t${threads}-r${round}-${side}-a${attempt}.log"
        ls="$(load1)"
        log "${bench} t=${threads} 第 ${round}/${rounds} 轮 ${side} 侧（尝试 ${attempt}，负载 ${ls}）"
        if "${java_bin}" -jar "${out_dir}/${side}/benchmarks.jar" "${regex}" \
            -f "${forks}" -wi "${warmup_iterations}" -w "${warmup_time}" -i "${iterations}" -r "${measure_time}" \
            -t "${threads}" -foe true -rf json -rff "${json}" ${jvm_args[@]+"${jvm_args[@]}"} ${jmh_params[@]+"${jmh_params[@]}"} \
            >"${logf}" 2>&1; then
            rc=0
        else
            rc=$?
        fi
        # 不经本脚本启动的 JMH（持有 JMH 自己的锁）也会干扰测量：等它结束，不消耗重试次数。
        if [ "${rc}" -ne 0 ] && grep -q "Unable to acquire the JMH lock" "${logf}" 2>/dev/null; then
            log "  → 本机另有 JMH 实例在运行（未经本脚本），30s 后重试"
            rm -f "${json}" "${logf}"
            attempt=$((attempt - 1))
            sleep 30
            continue
        fi
        le="$(load1)"
        status="ok"
        reason="${gate}"
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
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "${bench}" "${threads}" "${round}" "${side}" "${attempt}" "${status}" "${reason}" \
            "${ls}" "${le}" "${cv}" "${json}" >>"${runs_tsv}"
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
log "基线 ${base_ref} (${base_sha})，新侧为工作区 (${new_sha})；输出目录 ${out_dir}"
log "基准 ${benches}；线程 ${threads_list}；${rounds} 轮；负载门槛 ${load_threshold}（${ncpu} 核）"

acquire_lock
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
    regex="^.*\\.${bench}\$"
    nb="$(count_matches base "${regex}")"
    nn="$(count_matches new "${regex}")"
    if [ "${nb}" -eq 1 ] && [ "${nn}" -eq 1 ]; then
        run_benches+=("${bench}")
    else
        log "跳过 ${bench}：基线匹配 ${nb} 个、新侧匹配 ${nn} 个（需各恰好 1 个）"
        skipped="${skipped:+${skipped}, }${bench}"
    fi
done
[ "${#run_benches[@]}" -gt 0 ] || die "没有可对比的基准"

printf 'bench\tthreads\tround\tside\tattempt\tstatus\treason\tload_start\tload_end\tcv\tjson\n' >"${runs_tsv}"
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
    "threshold=${load_threshold}" "ncpu=${ncpu}" "max_cv=${max_cv}" "margin=${rise_margin}" \
    "retries=${max_retries}" "rounds=${rounds}" "skipped=${skipped}" \
    "jmh=fork ${forks}，预热 ${warmup_iterations}×${warmup_time}，测量 ${iterations}×${measure_time}"
log "完成：${out_dir}/summary.md（原始 JSON 在 ${out_dir}/raw）"
