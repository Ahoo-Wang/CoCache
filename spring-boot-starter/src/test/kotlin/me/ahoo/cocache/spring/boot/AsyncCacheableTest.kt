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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 返回 `CompletableFuture` 的 `@Cacheable(sync = true)`（Spring `retrieve(key, valueLoader)`，RFC 0004 §8 / §13）：
 * 方法体在运行时的执行器（`cocache.executor`）上执行；嵌套的缓存方法不死锁，先阻塞再返回的方法体也不会饿死其它缓存。
 */
class AsyncCacheableTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(CoCacheAutoConfiguration::class.java, CoCacheSpringCacheAutoConfiguration::class.java)
        )
        .withUserConfiguration(CachingConfig::class.java)
        .withPropertyValues(
            "cocache.backend=memory",
            "cocache.executor=testExecutor",
        )

    open class InnerService(private val threads: ConcurrentLinkedQueue<String>, private val gate: CountDownLatch) {
        @Cacheable(cacheNames = ["inner"], sync = true)
        open fun find(id: String): CompletableFuture<String> {
            threads.add(Concurrency.currentThreadName())
            return CompletableFuture.completedFuture("i-$id")
        }

        /** 先阻塞再返回（如 `completedFuture(repository.find(id))`）。 */
        @Cacheable(cacheNames = ["inner"], sync = true)
        open fun blocking(id: Long): CompletableFuture<String> {
            threads.add(Concurrency.currentThreadName())
            gate.await()
            return CompletableFuture.completedFuture("b-$id")
        }
    }

    open class OuterService(@Lazy private val inner: InnerService) {
        /** 嵌套并衔接内层的 future。 */
        @Cacheable(cacheNames = ["outer"], sync = true)
        open fun composed(id: String): CompletableFuture<String> = inner.find(id).thenApply { "o-$it" }

        /** 嵌套并在方法体里等待内层的 future。 */
        @Cacheable(cacheNames = ["outer"], sync = true)
        open fun joined(id: Long): CompletableFuture<String> =
            CompletableFuture.completedFuture("j-" + inner.find("j$id").join())
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class CachingConfig {
        @Bean(destroyMethod = "shutdownNow")
        fun testExecutor(): ExecutorService {
            val counter = AtomicInteger()
            return Executors.newCachedThreadPool {
                Thread(it, "test-exec-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        @Bean
        fun bodyThreads(): ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()

        @Bean
        fun gate(): CountDownLatch = CountDownLatch(1)

        @Bean
        fun innerSpec(): CacheSpec<String, String> = cacheSpec("inner") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun outerSpec(): CacheSpec<String, String> = cacheSpec("outer") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
        }

        @Bean
        fun innerService(
            bodyThreads: ConcurrentLinkedQueue<String>,
            gate: CountDownLatch
        ): InnerService = InnerService(bodyThreads, gate)

        @Bean
        fun outerService(innerService: InnerService): OuterService = OuterService(innerService)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyThreads(context: org.springframework.context.ApplicationContext) =
        context.getBean("bodyThreads") as ConcurrentLinkedQueue<String>

    @Test
    fun methodBodyRunsOnTheExecutor() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(InnerService::class.java).find("1").get(5, TimeUnit.SECONDS).assert().isEqualTo("i-1")
            bodyThreads(context).single().assert().startsWith("test-exec-")
            // 命中：不再调用方法体
            context.getBean(InnerService::class.java).find("1").get(5, TimeUnit.SECONDS).assert().isEqualTo("i-1")
            bodyThreads(context).size.assert().isEqualTo(1)
        }
    }

    @Test
    fun nestedCacheableDoesNotDeadlock() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val outer = context.getBean(OuterService::class.java)
            // 超时即视为死锁
            outer.composed("1").get(10, TimeUnit.SECONDS).assert().isEqualTo("o-i-1")
            outer.joined(2).get(10, TimeUnit.SECONDS).assert().isEqualTo("j-i-j2")
            bodyThreads(context).forEach { it.assert().startsWith("test-exec-") }
            bodyThreads(context).size.assert().isEqualTo(2)
        }
    }

    @Test
    fun blockingMethodBodyDoesNotStarveOtherCaches() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val gate = context.getBean(CountDownLatch::class.java)
            try {
                val blocked = context.getBean(InnerService::class.java).blocking(1)
                // 方法体占住执行器的一个线程；其它缓存的异步未命中照常完成

                @Suppress("UNCHECKED_CAST")
                val user = context.getBean("user") as Cache<String, String>
                user.async().get("1").get(10, TimeUnit.SECONDS).assert().isEqualTo("user-1")
                user.async().get("1").get(10, TimeUnit.SECONDS).assert().isEqualTo("user-1")
                blocked.isDone.assert().isFalse()
                gate.countDown()
                blocked.get(10, TimeUnit.SECONDS).assert().isEqualTo("b-1")
            } finally {
                gate.countDown()
            }
        }
    }
}
