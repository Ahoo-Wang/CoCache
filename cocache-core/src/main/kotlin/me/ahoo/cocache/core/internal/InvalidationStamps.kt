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

import java.util.concurrent.atomic.AtomicLongArray

/**
 * 分段失效戳（L2 栅栏）：每次失效递增 key 所在分段的计数。
 *
 * - 填充方：读 L1 之前取戳，写 L2 之前校验、之后复核，失败则撤销。
 * - 失效方：在修改上游（L1）之前**和之后**各递增一次，并淘汰 L2（RFC 0003 §4.4）。
 *
 * 分段碰撞不影响正确性，代价是多余地放弃一次填充。
 */
internal class InvalidationStamps(stripes: Int = DEFAULT_STRIPES) {
    init {
        require(stripes > 0 && stripes and (stripes - 1) == 0) { "stripes[$stripes] must be a power of two." }
    }

    private val stamps = AtomicLongArray(stripes)
    private val mask = stripes - 1

    fun current(key: String): Long = stamps.get(indexOf(key))

    fun isValid(key: String, stamp: Long): Boolean = current(key) == stamp

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

    companion object {
        const val DEFAULT_STRIPES = 4096
    }
}
