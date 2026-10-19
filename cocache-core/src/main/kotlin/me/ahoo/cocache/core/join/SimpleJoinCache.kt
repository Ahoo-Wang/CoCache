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

package me.ahoo.cocache.core.join

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinValue

/**
 * 组合两个独立缓存的 [JoinCache]：只读、无状态，不持有组件缓存的生命周期。
 *
 * 读取全部委托给组件缓存，因此组件缓存的一致性保证（栅栏、失效、限龄）原样适用；两次读取之间没有原子性。
 */
class SimpleJoinCache<K1 : Any, V1 : Any, K2 : Any, V2 : Any>(
    override val name: String,
    val firstCache: Cache<K1, V1>,
    val joinCache: Cache<K2, V2>,
    val joinKeyExtractor: JoinKeyExtractor<V1, K2>,
) : JoinCache<K1, V1, K2, V2> {

    override fun get(key: K1): JoinValue<V1, K2, V2>? {
        val firstValue = firstCache[key] ?: return null
        val joinKey = joinKeyExtractor.extract(firstValue)
        return JoinValue(firstValue, joinKey, joinKey?.let { joinCache[it] })
    }

    override fun getAll(keys: Collection<K1>): Map<K1, JoinValue<V1, K2, V2>> {
        val firstValues = firstCache.getAll(keys)
        if (firstValues.isEmpty()) {
            return emptyMap()
        }
        val joinKeys = LinkedHashMap<K1, K2?>(firstValues.size)
        for ((key, firstValue) in firstValues) {
            joinKeys[key] = joinKeyExtractor.extract(firstValue)
        }
        val distinctJoinKeys = joinKeys.values.filterNotNullTo(LinkedHashSet())
        val secondValues = if (distinctJoinKeys.isEmpty()) emptyMap() else joinCache.getAll(distinctJoinKeys)
        val result = LinkedHashMap<K1, JoinValue<V1, K2, V2>>(firstValues.size)
        for ((key, firstValue) in firstValues) {
            val joinKey = joinKeys[key]
            result[key] = JoinValue(firstValue, joinKey, joinKey?.let { secondValues[it] })
        }
        return result
    }

    /**
     * 只失效主缓存：关联缓存有独立的生命周期，由其写入方负责失效。
     */
    override fun invalidate(key: K1) {
        firstCache.invalidate(key)
    }

    /**
     * 两者都会尝试失效：一方抛出时另一方仍然执行，异常合并后抛出。
     */
    override fun invalidate(firstKey: K1, joinKey: K2) {
        val failure = runCatching { firstCache.invalidate(firstKey) }.exceptionOrNull()
        val joinFailure = runCatching { joinCache.invalidate(joinKey) }.exceptionOrNull()
        val error = failure ?: joinFailure ?: return
        if (failure != null && joinFailure != null) {
            error.addSuppressed(joinFailure)
        }
        throw error
    }

    override fun toString(): String {
        return "SimpleJoinCache(name='$name', firstCache=${firstCache.name}, joinCache=${joinCache.name})"
    }
}
