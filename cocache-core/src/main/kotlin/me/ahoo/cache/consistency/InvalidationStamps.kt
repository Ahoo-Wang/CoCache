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

package me.ahoo.cache.consistency

import java.util.concurrent.atomic.AtomicLongArray

/**
 * 分段失效戳：每次失效递增 key 所在分段的计数。
 *
 * 写回（L1→L2 填充、回源写回）前取戳，写回前后各校验一次：戳变化说明期间发生过失效，
 * 写回的值可能已陈旧，必须放弃或撤销。读取方还用它拒绝共享“开始于自身已观察到的失效之前”的加载结果。
 *
 * 分段碰撞（其它 key 的失效）不影响正确性，代价是：多余地放弃一次写回；若恰好落在写入与复核之间，
 * 还会多一次 L1 淘汰与失效广播。
 *
 * 失效方必须先递增戳、再淘汰副本；写回方必须先写入、再复核戳——二者交错时总有一方会清除陈旧副本。
 */
internal class InvalidationStamps(stripes: Int = DEFAULT_STRIPES) {
    companion object {
        const val DEFAULT_STRIPES = 4096
    }

    init {
        require(stripes > 0 && stripes and (stripes - 1) == 0) { "stripes[$stripes] must be a power of two." }
    }

    private val stamps = AtomicLongArray(stripes)
    private val mask = stripes - 1

    fun current(key: String): Long {
        return stamps.get(indexOf(key))
    }

    fun isValid(key: String, stamp: Long): Boolean {
        return current(key) == stamp
    }

    fun invalidate(key: String) {
        stamps.incrementAndGet(indexOf(key))
    }

    fun invalidateAll() {
        for (index in 0 until stamps.length()) {
            stamps.incrementAndGet(index)
        }
    }

    private fun indexOf(key: String): Int {
        val hash = key.hashCode()
        return (hash xor (hash ushr 16)) and mask
    }
}
