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
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture

/**
 * Spring Cache 与用户 API 类型（RFC 0005 §4.9）：`Lookup.Unknown` 在同步、`CompletableFuture` 与响应式三条路径上一致地交给
 * `CacheErrorHandler`，`unknownRead = ABSENT` 时视为未命中（U5）；指向不是 `Cache` 的缓存的操作启动失败。
 */
class SpringCacheTypesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))

    open class ProfileReader {
        @Cacheable(cacheNames = ["profile"], key = "#id")
        open fun sync(id: String): String = "profile-$id"

        @Cacheable(cacheNames = ["profile"], key = "#id")
        open fun future(id: String): CompletableFuture<String> = CompletableFuture.completedFuture("profile-$id")

        @Cacheable(cacheNames = ["profile"], key = "#id")
        open fun mono(id: String): Mono<String> = Mono.just("profile-$id")

        @Cacheable(cacheNames = ["lenient"], key = "#id")
        open fun lenientSync(id: String): String = "lenient-$id"

        @Cacheable(cacheNames = ["lenient"], key = "#id")
        open fun lenientFuture(id: String): CompletableFuture<String> = CompletableFuture.completedFuture("lenient-$id")

        @Cacheable(cacheNames = ["lenient"], key = "#id")
        open fun lenientMono(id: String): Mono<String> = Mono.just("lenient-$id")
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class UnknownConfig {
        @Bean(destroyMethod = "close")
        fun hookedBackend(): CoherenceBackend = HookedBackend(InMemoryBackend()).apply { closesDelegate = true }

        @Bean
        fun profileSpec(): CacheSpec<String, String> = cacheSpec("profile") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun lenientSpec(): CacheSpec<String, String> = cacheSpec("lenient") {
            codec = StringCodec
            externallySourced()
            unknownRead = UnknownReadMode.ABSENT
        }

        @Bean
        fun recordingErrorHandler(): TransactionalCacheTest.RecordingErrorHandler = TransactionalCacheTest.RecordingErrorHandler()

        @Bean
        fun cachingConfigurer(handler: TransactionalCacheTest.RecordingErrorHandler): CachingConfigurer =
            object : CachingConfigurer {
                override fun errorHandler(): CacheErrorHandler = handler
            }

        @Bean
        fun profileReader(): ProfileReader = ProfileReader()
    }

    /**
     * U5：L1 故障时三条路径都把 `CacheReadException` 交给 `CacheErrorHandler`（处理器放行后方法照常执行）；
     * 声明 `unknownRead = ABSENT` 的缓存按未命中执行方法，不经处理器。
     */
    @Test
    fun unknownGoesToTheErrorHandlerOnEveryPath() {
        runner.withUserConfiguration(UnknownConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val backend = context.getBean(HookedBackend::class.java)
            val handler = context.getBean(TransactionalCacheTest.RecordingErrorHandler::class.java)
            val reader = context.getBean(ProfileReader::class.java)
            backend.failAll = true
            reader.sync("1").assert().isEqualTo("profile-1")
            reader.future("2").join().assert().isEqualTo("profile-2")
            reader.mono("3").block().assert().isEqualTo("profile-3")
            reader.lenientSync("4").assert().isEqualTo("lenient-4")
            reader.lenientFuture("5").join().assert().isEqualTo("lenient-5")
            reader.lenientMono("6").block().assert().isEqualTo("lenient-6")
            backend.failAll = false
            handler.events.filter { it.startsWith("get:") }
                .assert().containsExactly("get:profile:1", "get:profile:2", "get:profile:3")
        }
    }

    open class MisdirectedService {
        @Cacheable(cacheNames = ["record"], key = "#id", sync = true)
        open fun record(id: String): String = "r-$id"

        @CacheEvict(cacheNames = ["view"], key = "#id")
        open fun evictView(id: String) = Unit

        /**
         * 不带 `sync`：只报告指向不是 `Cache` 的缓存，不另算 `sync` 的违规。
         */
        @Cacheable(cacheNames = ["ledger"], key = "#id")
        open fun ledger(id: String): String = "l-$id"

        @CachePut(cacheNames = ["members", "derived"], key = "#id")
        open fun putMembers(id: String): String = "m-$id"

        @Cacheable(cacheNames = ["derived"], key = "#id", sync = true)
        open fun derived(id: String): String = "d-$id"
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class MisdirectedConfig {
        @Bean
        fun misdirectedService(): MisdirectedService = MisdirectedService()
    }

    /**
     * 指向 `Store`、`SetStore`、`VersionedCache`（接口式声明）的任何缓存操作启动失败，不论 `sync`；延迟初始化（缓存尚未创建）时
     * 按声明的类型判断。指向派生副本的操作不受影响。
     */
    @Test
    fun operationsOnUnderivedCachesFailStartup() {
        val misdirected = runner.withUserConfiguration(
            ApiTypesTest.InterfaceConfig::class.java,
            MisdirectedConfig::class.java
        )
        listOf(
            misdirected,
            misdirected.withPropertyValues("spring.main.lazy-initialization=true")
        ).forEach { candidate ->
            candidate.run { context ->
                assertThat(context).hasFailed()
                val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }
                    .first { it.contains("not a Cache") }
                message.assert().contains(
                    "#record -> [record]",
                    "#evictView -> [view]",
                    "#putMembers -> [members]",
                    "#ledger -> [ledger]"
                )
                message.assert().doesNotContain("#derived")
            }
        }
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class StoreSpecConfig {
        @Bean
        fun recordSpec(): StoreSpec<String, String> = storeSpec("record") { codec = StringCodec }

        @Bean
        fun derivedSpec(): CacheSpec<String, String> = cacheSpec("derived") {
            codec = StringCodec
            loader = CacheLoader { it }
        }

        @Bean
        fun service(): MisdirectedService = MisdirectedService()
    }

    /**
     * spec bean 声明的权威副本同样检查（含延迟初始化）；只有指向派生副本的操作时照常启动。
     */
    @Test
    fun operationsOnStoreSpecsFailStartup() {
        val base = runner.withPropertyValues(
            "cocache.backend=memory"
        ).withUserConfiguration(StoreSpecConfig::class.java)
        listOf(base, base.withPropertyValues("spring.main.lazy-initialization=true")).forEach { candidate ->
            candidate.run { context ->
                assertThat(context).hasFailed()
                generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }
                    .any { it.contains("#record -> [record]") }.assert().isTrue()
            }
        }
    }
}
