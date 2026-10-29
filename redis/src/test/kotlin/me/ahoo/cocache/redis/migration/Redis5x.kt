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

package me.ahoo.cocache.redis.migration

import me.ahoo.cocache.redis.RedisTestSupport

/**
 * 按 CoCache 5.x（5.0–5.4，`cocache-spring-redis` 的 `*CodecExecutor`，`StringRedisTemplate` 以 UTF-8 写入）的线格式写入测试数据：
 * - `StringToStringCodecExecutor` / `ObjectToJsonCodecExecutor`：String，`SET key value [EX ttl]`；
 * - `SetToSetCodecExecutor`：原生 Set，`SADD` + 可选 `EXPIRE`；
 * - `MapToHashCodecExecutor` / `ObjectToHashCodecExecutor`：Hash，`HSET` + 可选 `EXPIRE`；
 * - 负缓存：String 为哨兵本身，Set 为只含哨兵的单元素集合，Hash 为 `{哨兵: 写入时间}`。
 */
object Redis5x {
    const val SENTINEL = "_nil_"

    fun string(key: String, value: String, ttlSeconds: Long? = null) = RedisTestSupport.withCommands { commands ->
        if (ttlSeconds == null) commands.set(key, value) else commands.setex(key, ttlSeconds, value)
    }

    fun set(key: String, members: Collection<String>, ttlSeconds: Long? = null) = RedisTestSupport.withCommands { commands ->
        commands.del(key)
        commands.sadd(key, *members.toTypedArray())
        ttlSeconds?.let { commands.expire(key, it) }
    }

    fun hash(key: String, fields: Map<String, String>, ttlSeconds: Long? = null) = RedisTestSupport.withCommands { commands ->
        commands.del(key)
        commands.hset(key, fields)
        ttlSeconds?.let { commands.expire(key, it) }
    }

    fun missingString(key: String, ttlSeconds: Long = 60) = string(key, SENTINEL, ttlSeconds)

    fun missingSet(key: String, ttlSeconds: Long = 60) = set(key, listOf(SENTINEL), ttlSeconds)

    fun missingHash(key: String, ttlSeconds: Long = 60) = hash(key, mapOf(SENTINEL to "1700000000"), ttlSeconds)

    fun exists(key: String): Boolean = RedisTestSupport.withCommands { it.exists(key) == 1L }

    fun type(key: String): String = RedisTestSupport.withCommands { it.type(key) }

    fun pttl(key: String): Long = RedisTestSupport.withCommands { it.pttl(key) }

    fun get(key: String): String? = RedisTestSupport.withCommands { it.get(key) }

    fun members(key: String): Set<String> = RedisTestSupport.withCommands { it.smembers(key) }

    fun fields(key: String): Map<String, String> = RedisTestSupport.withCommands { it.hgetall(key) }

    fun delete(vararg keys: String) = RedisTestSupport.withCommands { commands -> keys.forEach { commands.del(it) } }
}
