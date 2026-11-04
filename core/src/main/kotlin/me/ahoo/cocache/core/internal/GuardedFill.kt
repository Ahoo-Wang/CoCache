/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cocache.core.internal

import java.util.concurrent.ConcurrentMap

/**
 * 戳保护的 L2 填充（RFC 0005 §4.5、附录 A.D4）：在 key 的锁内校验并写入，写后按身份复核。
 *
 * - 写入方先递增戳、再淘汰 L2（单个 key 的淘汰与 `compute` 在同一个桶的锁上串行化）。校验与写入在锁内是一步：
 *   写入方的递增若早于这一步，这里不写；若晚于这一步，写入方随后的淘汰删掉这一份。所以写入方完成之后，旧值不会再出现在 L2 中。
 *   锁外的“校验 → 写入 → 复核 → 撤销”在校验与写入之间有窗口：校验刚通过时整个写入方完成，旧值随后写入，复核撤销之前对读取可见。
 * - 重置与 `clearLocal` 按快照清空，看不到进行中的 `compute`：由写后复核撤销。这是程序顺序上的界，不是时间上的界（Q11）。
 * - 复核按身份撤销（`remove(key, entry)`）：只撤销自己写入的那一份，不删掉其间别的填充写入的副本。
 *   因此 [entry] 的类型不得按值比较（`LocalEntry` 不是 data class，也不重写 `equals`）。
 * - 进入 `compute` 之前先校验一次，失败就不进（省掉一次加锁与访问时间的刷新）。
 * - [kept] 在同一步中决定是否保留已有的副本（带版本的缓存：已有的副本版本更新时保留，RFC 0005 I-K 的 L2 条款）：返回要留下的副本
 *   （已有的那一份，或它的一份改了刷新点的副本），`null` 表示照常写入 [entry]。写入的若是新的副本，同样按身份复核。
 *
 * @param valid 填充所取的戳是否仍然有效；会被调用至多三次
 * @param kept 已有的副本应当保留时返回要留下的副本，否则为 `null`；在锁内调用，不得有副作用
 * @return 写入 [entry] 且保留时为 `true`；校验失败（没有写入）、保留了已有的副本，或复核失败（已撤销自己写入的那一份）时为 `false`
 */
internal fun <V : Any> ConcurrentMap<String, V>.fillGuarded(
    key: String,
    entry: V,
    kept: (V) -> V? = { null },
    valid: () -> Boolean
): Boolean {
    if (!valid()) {
        return false
    }
    var previous: V? = null
    val written = compute(key) { _, current ->
        previous = current
        if (valid()) current?.let(kept) ?: entry else current
    }
    if (written == null || written === previous) {
        return false
    }
    if (!valid()) {
        remove(key, written)
        return false
    }
    return written === entry
}
