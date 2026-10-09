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
 * Redis 编解码执行器：负责一种 Redis 数据结构上的读写。
 *
 * @author ahoo wang
 */
interface CodecExecutor<V> {
    /**
     * 一次往返读取值及其剩余 TTL。
     *
     * @return 命中值或负缓存；`null` 表示未命中（key 不存在、读取期间被删除、或载荷损坏已自愈淘汰），调用方必须回源
     */
    fun executeAndDecode(key: String): CacheValue<V>?

    /**
     * 写入条目：负缓存写为哨兵；已过期条目淘汰 key。
     */
    fun executeAndEncode(key: String, cacheValue: CacheValue<V>)
}
