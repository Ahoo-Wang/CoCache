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

package me.ahoo.cache.spring.redis

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.distributed.SetElementsStore
import me.ahoo.cache.spring.redis.codec.SetToSetCodecExecutor
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 以 Redis Set 存储 `Set<String>` 的分布式缓存，支持原子元素增删
 * （[SetElementsCache][me.ahoo.cache.api.SetElementsCache]）。线格式与 [SetToSetCodecExecutor] 相同。
 *
 * 故障降级同 [RedisDistributedCache]：增删失败仅告警；条件写回失败视为未写入。
 *
 * @author ahoo wang
 */
class RedisSetDistributedCache @JvmOverloads constructor(
    redisTemplate: StringRedisTemplate,
    private val codecExecutor: SetToSetCodecExecutor = SetToSetCodecExecutor(redisTemplate),
    strictFailure: Boolean = false,
) : RedisDistributedCache<Set<String>>(redisTemplate, codecExecutor, strictFailure), SetElementsStore<String> {

    override fun addElements(key: String, elements: Collection<String>, ttlAt: Long) {
        try {
            codecExecutor.executeAddElements(key, elements, ttlAt)
        } catch (e: DataAccessException) {
            handleFailure("addElements", key, e)
        }
    }

    override fun removeElements(key: String, elements: Collection<String>, ttlAt: Long) {
        try {
            codecExecutor.executeRemoveElements(key, elements, ttlAt)
        } catch (e: DataAccessException) {
            handleFailure("removeElements", key, e)
        }
    }

    override fun fillCache(key: String, value: CacheValue<Set<String>>): Boolean {
        return try {
            codecExecutor.executeFill(key, value)
        } catch (e: DataAccessException) {
            handleFailure("fillCache", key, e)
            false
        }
    }

    override fun evictIfUnchanged(key: String, value: CacheValue<Set<String>>) {
        try {
            codecExecutor.executeEvictIfUnchanged(key, value)
        } catch (e: DataAccessException) {
            handleFailure("evictIfUnchanged", key, e)
        }
    }
}
