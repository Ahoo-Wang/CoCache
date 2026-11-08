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
import me.ahoo.cocache.spring.CacheNameContributor
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 库声明可能在运行时创建的缓存名（X1）：贡献的名称不算“未声明的缓存”，其余名称照常校验；延迟初始化的贡献者同样被读取。
 */
class CacheNameContributorTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.backend=memory", "cocache.caches.[cosec.token].ttl=60s")

    @Configuration(proxyBeanMethods = false)
    class LibraryConfig {
        @Bean
        fun tokenCacheNames(): CacheNameContributor = CacheNameContributor {
            READS.incrementAndGet()
            listOf("Cosec.Token", "cosec.permission")
        }
    }

    @Test
    fun anUndeclaredNameFailsStartupAndPointsAtTheContributor() {
        runner.run { context ->
            assertThat(context).hasFailed()
            val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }
                .first { it.contains("undeclared caches") }
            message.assert().contains("[cosec.token]", "CacheNameContributor")
        }
    }

    /**
     * 贡献的名称（不区分大小写）不让启动失败；库在启动之后创建这个缓存时，配置照常生效。
     */
    @Test
    fun aContributedNameIsDeclared() {
        runner.withUserConfiguration(LibraryConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.cacheNames.assert().doesNotContain("cosec.token")
            context.getBean(CoCacheFactory::class.java).create(
                cacheSpec<String, String>("cosec.token") {
                    codec = StringCodec
                    loader = CacheLoader { it }
                }
            )
            runtime.policy("cosec.token")?.ttl.assert().isEqualTo(Duration.ofSeconds(60))
        }
    }

    /**
     * 只放宽贡献的名称：其余未声明的名称照常让启动失败，错误中不列出贡献的名称为未声明。
     */
    @Test
    fun onlyTheContributedNamesAreRelaxed() {
        runner.withUserConfiguration(LibraryConfig::class.java)
            .withPropertyValues("cocache.caches.typo.ttl=1s")
            .run { context ->
                assertThat(context).hasFailed()
                val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }
                    .first { it.contains("undeclared caches") }
                message.assert().startsWith("cocache.caches configures undeclared caches [typo];")
            }
    }

    /**
     * 延迟初始化：贡献者 bean 没有注入点，名称校验在全部单例创建之后取得它（这时创建），而不是看不到它。
     */
    @Test
    fun aLazyContributorIsStillRead() {
        val before = READS.get()
        runner.withUserConfiguration(LibraryConfig::class.java)
            .withInitializer { it.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor()) }
            .run { context ->
                assertThat(context).hasNotFailed()
                (READS.get() - before).assert().isEqualTo(1)
            }
    }

    companion object {
        val READS = AtomicInteger()
    }
}
