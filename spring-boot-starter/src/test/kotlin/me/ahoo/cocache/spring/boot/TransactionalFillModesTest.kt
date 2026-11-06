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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.tx.FillInTransaction
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * 写事务内回源的两个机制的生效值只由创建成功的缓存登记（#842）：分层时暂存在这次分层的报告上，缓存注册成功之后才生效。
 * 重名而失败的创建、只解析不创建（驱逐检查用的 [CacheAssembler.resolve]）都不改写已有缓存的模式。
 */
class TransactionalFillModesTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val modes = TransactionalFillModes()

    @AfterEach
    fun destroy() = runtime.close()

    private val relaxed = TransactionalFillModes.Mode(false, FillInTransaction.ALLOW)

    private fun factory(vararg entries: Pair<String, String>): Pair<CoCacheFactory, CacheAssembler> {
        val properties = Binder(MapConfigurationPropertySource(mapOf(*entries)))
            .bindOrCreate(CoCacheProperties.PREFIX, CoCacheProperties::class.java)
        val assembler = CacheAssembler(runtime, emptyList(), properties, fillModes = modes)
        return CoCacheFactory(runtime, assembler) to assembler
    }

    private fun orders() = cacheSpec<String, String>("orders") {
        codec = StringCodec
        loader = CacheLoader { it }
    }

    @Test
    fun aRejectedDuplicateDoesNotRewriteTheExistingCachesModes() {
        val existing = runtime.create(orders())
        modes.modeOf("orders").assert().isEqualTo(TransactionalFillModes.Mode.DEFAULT)
        val (factory, _) = factory(
            "cocache.defaults.cache.fill-in-transaction" to "allow",
            "cocache.defaults.cache.invalidate-fills-on-rollback" to "false",
        )
        runCatching { factory.create(orders()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage("Cache[orders] already exists.")
        runtime.cache("orders").assert().isSameAs(existing)
        modes.modeOf("orders").assert()
            .withFailMessage { "the failed creation must not change how the existing cache treats transactions" }
            .isEqualTo(TransactionalFillModes.Mode.DEFAULT)
    }

    @Test
    fun onlyASuccessfulCreationCommitsItsModes() {
        val (factory, assembler) = factory(
            "cocache.defaults.cache.fill-in-transaction" to "allow",
            "cocache.defaults.cache.invalidate-fills-on-rollback" to "false",
        )
        assembler.resolve(orders())
        modes.modeOf("orders").assert().withFailMessage {
            "resolving creates nothing"
        }.isEqualTo(TransactionalFillModes.Mode.DEFAULT)

        factory.create(orders())
        modes.modeOf("orders").assert().isEqualTo(relaxed)
        // 未分层的缓存没有分层报告：不登记，取类型默认值
        runtime.create(
            cacheSpec<String, String>("plain") {
                codec = StringCodec
                externallySourced()
            }
        )
        modes.modeOf("plain").assert().isEqualTo(TransactionalFillModes.Mode.DEFAULT)
    }
}
