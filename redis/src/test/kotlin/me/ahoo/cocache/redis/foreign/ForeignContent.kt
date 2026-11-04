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

package me.ahoo.cocache.redis.foreign

import me.ahoo.cocache.redis.RedisTestSupport

/**
 * 在缓存的 key 上写入不是本协议写入的内容（读不懂的内容，RFC 0005 §4.7），以及直接读取 Redis 中的原样内容：
 * - String：`SET key value [EX ttl]`，如其它程序写入的 JSON 或字符串；
 * - 原生 Set：`SADD` + 可选 `EXPIRE`；
 * - Hash：`HSET` + 可选 `EXPIRE`，也用来写出 `!f` 等字段取任意值的集合。
 */
object ForeignContent {
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

    fun exists(key: String): Boolean = RedisTestSupport.withCommands { it.exists(key) == 1L }

    fun type(key: String): String = RedisTestSupport.withCommands { it.type(key) }

    fun pttl(key: String): Long = RedisTestSupport.withCommands { it.pttl(key) }

    fun get(key: String): String? = RedisTestSupport.withCommands { it.get(key) }

    fun members(key: String): Set<String> = RedisTestSupport.withCommands { it.smembers(key) }

    fun fields(key: String): Map<String, String> = RedisTestSupport.withCommands { it.hgetall(key) }

    fun delete(vararg keys: String) = RedisTestSupport.withCommands { commands -> keys.forEach { commands.del(it) } }
}
