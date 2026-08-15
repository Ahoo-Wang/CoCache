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

import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * String To String Codec Executor Test .
 *
 * @author ahoo wang
 */
internal class StringToStringCodecExecutorTest : CodecExecutorSpec<String>() {
    override fun createCodecExecutor(): CodecExecutor<String> {
        return StringToStringCodecExecutor(stringRedisTemplate)
    }

    override fun createCacheValue(): String {
        return UUID.randomUUID().toString()
    }

    @BeforeEach
    override fun setup() {
        super.setup()
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
    }

    override fun createCustomSentinelCodecExecutor(): CodecExecutor<String> {
        return StringToStringCodecExecutor(stringRedisTemplate, CUSTOM_SENTINEL)
    }

    @Test
    fun customSentinelAvoidsCollision() {
        val executor = StringToStringCodecExecutor(
            stringRedisTemplate,
            missingGuardSentinel = CUSTOM_SENTINEL,
        )
        val key = "custom-sentinel:" + UUID.randomUUID().toString()

        // codec 层碰撞规避：外部写入的原始 "_nil_" 在自定义哨兵下按真实值读回（默认哨兵会误判为负缓存）。
        // 直接写入原始值：executeAndEncode 会把 isMissingGuard 为 true 的 CacheValue（含业务值
        // "_nil_"——核心层常量判定）编码为哨兵，无法产生 at-rest 的 "_nil_"，故模拟外部写入者视角。
        stringRedisTemplate.opsForValue()[key] = "_nil_"
        val decoded = executor.executeAndDecode(key, ComputedTtlAt.FOREVER)!!
        decoded.value.assert().isEqualTo("_nil_")

        // 固定残余语义：核心层 Any?.isMissingGuard 仍基于常量——"_nil_" 形状的 CacheValue
        // 在 core 各层（ComputedCache/client cache）依旧被视为负缓存，自定义哨兵不改变端到端读语义。
        decoded.isMissingGuard.assert().isTrue()

        // 负缓存写入使用自定义哨兵
        executor.executeAndEncode(key, DefaultCacheValue.missingGuard())
        stringRedisTemplate.opsForValue()[key].assert().isEqualTo(CUSTOM_SENTINEL)
        executor.executeAndDecode(key, ComputedTtlAt.FOREVER)!!.isMissingGuard.assert().isTrue()
    }
}
