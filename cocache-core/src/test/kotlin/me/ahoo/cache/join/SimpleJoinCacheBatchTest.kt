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

package me.ahoo.cache.join

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class SimpleJoinCacheBatchTest {

    /**
     * 只读的固定条目缓存，并记录批量读取的 key。
     */
    private class FixedCache<K, V>(private val entries: Map<K, CacheValue<V>>) : Cache<K, V> {
        val batches = mutableListOf<List<K>>()

        override fun getCache(key: K): CacheValue<V>? = entries[key]

        override fun getCaches(keys: Collection<K>): Map<K, CacheValue<V>> {
            batches.add(keys.toList())
            return super.getCaches(keys)
        }

        override fun setCache(key: K, value: CacheValue<V>) = Unit
        override fun set(key: K, value: V) = Unit
        override fun evict(key: K) = Unit
    }

    @Test
    fun getCachesJoinsInTwoBatches() {
        val firstCache = FixedCache(
            mapOf(
                "o1" to CacheValue.forever("u1"),
                "o2" to CacheValue.forever("u1"),
                "o3" to CacheValue.forever("u2"),
                "o4" to CacheValue.missing(TtlAt.at(60)),
                "o5" to CacheValue.of("u1", TtlAt.currentTime() - 1)
            )
        )
        val secondTtlAt = TtlAt.at(30)
        val joinCache = FixedCache(mapOf("u1" to CacheValue.of("User1", secondTtlAt)))
        val cache = SimpleJoinCache(firstCache, joinCache) { it }

        val actual = cache.getCaches(listOf("o1", "o2", "o3", "o4", "o5", "o6"))

        actual.keys.assert().containsExactly("o1", "o2", "o3", "o4")
        actual["o1"]?.value.assert().isEqualTo(JoinValue("u1", "u1", "User1"))
        actual["o1"]?.ttlAt.assert().isEqualTo(secondTtlAt)
        actual["o3"]?.value.assert().isEqualTo(JoinValue("u2", "u2", null))
        requireNotNull(actual["o4"]).isMissing.assert().isTrue()
        firstCache.batches.size.assert().isOne()
        joinCache.batches.assert().containsExactly(listOf("u1", "u2"))
    }
}
