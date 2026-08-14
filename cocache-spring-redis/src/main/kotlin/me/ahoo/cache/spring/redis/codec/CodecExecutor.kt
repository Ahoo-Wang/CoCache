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
package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.api.CacheValue

/**
 * Codec Executor .
 *
 * @author ahoo wang
 */
interface CodecExecutor<V> {
    /**
     * @param ttlAt time to live([java.time.temporal.ChronoUnit.SECONDS]).
     * @return 命中返回 [CacheValue]；负缓存返回 missing-guard；**载荷损坏或无法解码时返回 null**
     * （该 key 已被淘汰，调用方必须按缓存未命中处理——回源重建）。注意与 missing-guard 的区别：
     * missing-guard 会抑制回源，null 则触发回源。
     */
    fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V>?
    fun executeAndEncode(key: String, cacheValue: CacheValue<V>)
}
