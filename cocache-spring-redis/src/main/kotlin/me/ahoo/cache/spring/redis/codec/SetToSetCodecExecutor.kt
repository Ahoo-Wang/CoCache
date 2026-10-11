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
import me.ahoo.cache.api.MissingValue
import me.ahoo.cache.api.PresentValue
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

/**
 * `Set<String>` 存为 Redis Set。负缓存形态为单元素 `{sentinel}`；空 Set 写入即淘汰。
 *
 * 元素增删、条件写回与条件撤销各为一次 Lua 往返，线格式与整集写入相同；语义见
 * [SetElementsStore][me.ahoo.cache.api.distributed.SetElementsStore]。
 */
class SetToSetCodecExecutor @JvmOverloads constructor(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : AbstractCodecExecutor<Set<String>, Set<String>>(redisTemplate, missingGuardSentinel) {
    companion object {
        private val WRITE_SCRIPT: RedisScript<Long> = collectionWriteScript("SADD", step = 1)
        private val READ_SCRIPT: RedisScript<List<*>> = readScript("SMEMBERS")

        /**
         * 公共参数：ARGV[1] 哨兵、ARGV[2] TTL 秒数（0 表示永不过期）、ARGV[3..] 元素。
         * 负缓存判定与读取一致：恰好只含哨兵一个成员。
         */
        private const val IS_MISSING_GUARD =
            "redis.call('SCARD', KEYS[1]) == 1 and redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1"

        private const val APPLY_TTL =
            "if tonumber(ARGV[2]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) else redis.call('PERSIST', KEYS[1]) end"

        /**
         * 清除负缓存 → 逐个 SADD → 应用 TTL。
         */
        private val ADD_ELEMENTS_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            if $IS_MISSING_GUARD then redis.call('DEL', KEYS[1]) end
            for i = 3, #ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end
            $APPLY_TTL
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * key 不存在或为负缓存时不修改；逐个 SREM；移空时 Redis 自动删除 key，否则应用 TTL。
         */
        private val REMOVE_ELEMENTS_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 or $IS_MISSING_GUARD then return 0 end
            for i = 3, #ARGV do redis.call('SREM', KEYS[1], ARGV[i]) end
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            $APPLY_TTL
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * 仅当 key 不存在或为负缓存时整集写入（ARGV[3..] 为成员，负缓存为单个哨兵）。
         */
        private val FILL_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[1]) == 1 and not ($IS_MISSING_GUARD) then return 0 end
            redis.call('DEL', KEYS[1])
            for i = 3, #ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end
            if tonumber(ARGV[2]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * 仅当成员与 ARGV 完全相同时删除。
         */
        private val EVICT_IF_UNCHANGED_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            if redis.call('SCARD', KEYS[1]) ~= #ARGV then return 0 end
            for i = 1, #ARGV do
              if redis.call('SISMEMBER', KEYS[1], ARGV[i]) == 0 then return 0 end
            end
            redis.call('DEL', KEYS[1])
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }

    override val readScript: RedisScript<List<*>> = READ_SCRIPT

    override fun toRaw(elements: List<*>): Set<String>? {
        if (elements.isEmpty()) {
            return null
        }
        return elements.mapTo(LinkedHashSet()) { it as String }
    }

    override fun isMissingGuard(raw: Set<String>): Boolean {
        return raw.size == 1 && raw.first() == missingGuardSentinel
    }

    override fun decode(raw: Set<String>): Set<String> = raw

    override fun encodeMissingGuard(): Set<String> = setOf(missingGuardSentinel)

    override fun encode(value: Set<String>): Set<String> = value

    override fun writeRaw(key: String, raw: Set<String>, ttlSeconds: Long?) {
        executeCollectionWrite(WRITE_SCRIPT, key, raw.toList(), ttlSeconds)
    }

    /**
     * 原子加入元素，语义见 [SetElementsStore.addElements][me.ahoo.cache.api.distributed.SetElementsStore.addElements]。
     *
     * @throws IllegalArgumentException 元素等于负缓存哨兵（集合可能被读为负缓存）
     */
    fun executeAddElements(key: String, elements: Collection<String>, ttlAt: Long) {
        require(missingGuardSentinel !in elements) {
            "elements must not contain the missing guard sentinel[$missingGuardSentinel]."
        }
        if (elements.isEmpty()) {
            return
        }
        executeSetScript(ADD_ELEMENTS_SCRIPT, key, elements, ttlAt)
    }

    /**
     * 原子移除元素，语义见 [SetElementsStore.removeElements][me.ahoo.cache.api.distributed.SetElementsStore.removeElements]。
     */
    fun executeRemoveElements(key: String, elements: Collection<String>, ttlAt: Long) {
        if (elements.isEmpty()) {
            return
        }
        executeSetScript(REMOVE_ELEMENTS_SCRIPT, key, elements, ttlAt)
    }

    /**
     * 条件写回，语义见 [SetElementsStore.fillCache][me.ahoo.cache.api.distributed.SetElementsStore.fillCache]。
     */
    fun executeFill(key: String, cacheValue: CacheValue<Set<String>>): Boolean {
        if (cacheValue.isExpired) {
            return false
        }
        return executeSetScript(FILL_SCRIPT, key, rawOf(cacheValue), cacheValue.ttlAt) == 1L
    }

    /**
     * 条件撤销，语义见 [SetElementsStore.evictIfUnchanged][me.ahoo.cache.api.distributed.SetElementsStore.evictIfUnchanged]。
     */
    fun executeEvictIfUnchanged(key: String, cacheValue: CacheValue<Set<String>>) {
        redisTemplate.execute(EVICT_IF_UNCHANGED_SCRIPT, listOf(key), *rawOf(cacheValue).toTypedArray())
    }

    private fun rawOf(cacheValue: CacheValue<Set<String>>): Set<String> {
        return when (cacheValue) {
            is MissingValue -> encodeMissingGuard()
            is PresentValue -> encode(cacheValue.value)
        }
    }

    /**
     * ARGV：哨兵、TTL 秒数（0 表示永不过期）、元素。
     */
    private fun executeSetScript(
        script: RedisScript<Long>,
        key: String,
        elements: Collection<String>,
        ttlAt: Long
    ): Long? {
        val ttlSeconds = ttlSecondsOf(ttlAt) ?: 0
        val args = ArrayList<String>(elements.size + 2)
        args.add(missingGuardSentinel)
        args.add(ttlSeconds.toString())
        args.addAll(elements)
        return redisTemplate.execute(script, listOf(key), *args.toTypedArray())
    }
}
