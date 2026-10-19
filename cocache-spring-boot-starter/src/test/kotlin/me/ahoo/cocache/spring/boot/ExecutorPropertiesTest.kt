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
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `cocache.refresh-executor` 与 `cocache.spring-cache.retrieve-executor`：按 bean 名解析、实际被使用、名称错误时启动失败。
 */
class ExecutorPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(SpecConfig::class.java)
        .withPropertyValues("cocache.backend=memory")

    /**
     * 记录任务数并在调用方线程上执行。
     */
    class RecordingExecutor : Executor {
        val tasks = AtomicInteger()

        override fun execute(command: Runnable) {
            tasks.incrementAndGet()
            command.run()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class SpecConfig {
        @Bean
        fun clock(): ManualClock = ManualClock()

        /** 每次回源所在的线程名。 */
        @Bean
        fun loaderThreads(): ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()

        @Bean
        fun userSpec(loaderThreads: ConcurrentLinkedQueue<String>): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader {
                loaderThreads.add(Thread.currentThread().name)
                "user-$it"
            }
            policy = CachePolicy(
                ttl = Duration.ofMinutes(10),
                ttlJitter = Duration.ZERO,
                localMaxAge = Duration.ofMinutes(10),
                refreshWindow = Duration.ofMinutes(1),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ExecutorConfig {
        @Bean
        fun refreshRecorder(): RecordingExecutor = RecordingExecutor()

        @Bean
        fun retrieveRecorder(): RecordingExecutor = RecordingExecutor()

        @Bean
        fun notAnExecutor(): StringBuilder = StringBuilder("plain")
    }

    @Configuration(proxyBeanMethods = false)
    class PoolConfig {
        /** 不让容器关闭它，以便验证运行时关闭时也不关闭它。 */
        @Bean(destroyMethod = "")
        fun refreshPool(): ExecutorService = Executors.newSingleThreadExecutor()
    }

    @Suppress("UNCHECKED_CAST")
    private fun userCache(context: org.springframework.context.ApplicationContext) =
        context.getBean("user") as Cache<String, String>

    /** 首次读回源，推进到刷新窗口内再读，触发一次提前刷新。 */
    private fun triggerRefresh(context: org.springframework.context.ApplicationContext) {
        val user = userCache(context)
        user["1"].assert().isEqualTo("user-1")
        context.getBean(ManualClock::class.java).advance(Duration.ofSeconds(570))
        user["1"].assert().isEqualTo("user-1")
    }

    private fun retrieveValue(cache: org.springframework.cache.Cache, key: String): Any? {
        val wrapper = cache.retrieve(key)?.get(5, TimeUnit.SECONDS) as org.springframework.cache.Cache.ValueWrapper?
        return wrapper?.get()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loaderThreads(context: org.springframework.context.ApplicationContext) =
        context.getBean("loaderThreads") as ConcurrentLinkedQueue<String>

    private fun awaitLoads(threads: ConcurrentLinkedQueue<String>, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (threads.size < count && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        threads.size.assert().withFailMessage { "expected $count loads, got $threads" }.isEqualTo(count)
    }

    @Test
    fun propertiesBindAndDefaultToNull() {
        runner.withPropertyValues(
            "cocache.refresh-executor=refreshRecorder",
            "cocache.spring-cache.retrieve-executor=retrieveRecorder",
        ).withUserConfiguration(ExecutorConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.refreshExecutor.assert().isEqualTo("refreshRecorder")
            properties.springCache.retrieveExecutor.assert().isEqualTo("retrieveRecorder")
        }
        runner.run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.refreshExecutor.assert().isNull()
            properties.springCache.retrieveExecutor.assert().isNull()
            properties.springCache.requireSync.assert().isFalse()
        }
    }

    @Test
    fun refreshExecutorBeanRunsRefreshAhead() {
        runner.withPropertyValues("cocache.refresh-executor=refreshRecorder")
            .withUserConfiguration(ExecutorConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                triggerRefresh(context)
                // 记录执行器在调用方线程上执行刷新：回源同步完成
                context.getBean("refreshRecorder", RecordingExecutor::class.java).tasks.get().assert().isEqualTo(1)
                context.getBean("retrieveRecorder", RecordingExecutor::class.java).tasks.get().assert().isEqualTo(0)
                loaderThreads(context).toList().assert().containsExactly(
                    Thread.currentThread().name,
                    Thread.currentThread().name
                )
            }
    }

    @Test
    fun defaultRefreshExecutorIsBuiltIn() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            triggerRefresh(context)
            val threads = loaderThreads(context)
            awaitLoads(threads, 2)
            threads.last().assert().startsWith("cocache-refresh-")
        }
    }

    @Test
    fun retrieveExecutorBeanRunsSpringCacheRetrieve() {
        runner.withPropertyValues("cocache.spring-cache.retrieve-executor=retrieveRecorder")
            .withUserConfiguration(ExecutorConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val manager = context.getBean(CacheManager::class.java)
                // 事务感知包装保持不变
                (manager is org.springframework.cache.transaction.TransactionAwareCacheManagerProxy).assert().isTrue()
                val cache = requireNotNull(manager.getCache("user"))
                retrieveValue(cache, "1").assert().isEqualTo("user-1")
                cache.retrieve("2") { java.util.concurrent.CompletableFuture.completedFuture("ignored") }
                    .get(5, TimeUnit.SECONDS).assert().isEqualTo("ignored")
                context.getBean("retrieveRecorder", RecordingExecutor::class.java).tasks.get().assert().isEqualTo(2)
                context.getBean("refreshRecorder", RecordingExecutor::class.java).tasks.get().assert().isEqualTo(0)
            }
    }

    @Test
    fun defaultRetrieveExecutorIsBuiltIn() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val cache = requireNotNull(context.getBean(CacheManager::class.java).getCache("user"))
            retrieveValue(cache, "1").assert().isEqualTo("user-1")
            // JDK 21+ 为虚拟线程（无名），否则为内置守护线程池
            val thread = loaderThreads(context).single()
            (thread.isEmpty() || thread.startsWith("cocache-spring-retrieve-")).assert()
                .withFailMessage { "retrieve ran on $thread" }.isTrue()
        }
    }

    @Test
    fun springBootApplicationTaskExecutorCanBeUsed() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java
                )
            )
            .withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues(
                "cocache.backend=memory",
                "cocache.spring-cache.retrieve-executor=applicationTaskExecutor",
                "cocache.refresh-executor=applicationTaskExecutor",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val cache = requireNotNull(context.getBean(CacheManager::class.java).getCache("user"))
                retrieveValue(cache, "1").assert().isEqualTo("user-1")
                val threads = loaderThreads(context)
                // 默认线程名前缀 task-
                threads.single().assert().startsWith("task-")
                context.getBean(ManualClock::class.java).advance(Duration.ofSeconds(570))
                userCache(context)["1"].assert().isEqualTo("user-1")
                awaitLoads(threads, 2)
                threads.last().assert().startsWith("task-")
            }
    }

    @Test
    fun unknownExecutorBeanFailsStartup() {
        mapOf(
            "cocache.refresh-executor" to "missingRefresh",
            "cocache.spring-cache.retrieve-executor" to "missingRetrieve",
        ).forEach { (property, name) ->
            runner.withPropertyValues("$property=$name").run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("$property=$name")
                    .hasStackTraceContaining("no bean named '$name'")
            }
        }
    }

    @Test
    fun nonExecutorBeanFailsStartup() {
        runner.withPropertyValues("cocache.spring-cache.retrieve-executor=notAnExecutor")
            .withUserConfiguration(ExecutorConfig::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert()
                    .hasStackTraceContaining("cocache.spring-cache.retrieve-executor=notAnExecutor")
                    .hasStackTraceContaining("java.lang.StringBuilder, not a java.util.concurrent.Executor")
            }
    }

    @Test
    fun runtimeDoesNotShutDownUserExecutor() {
        lateinit var pool: ExecutorService
        runner.withPropertyValues("cocache.refresh-executor=refreshPool")
            .withUserConfiguration(PoolConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                pool = context.getBean("refreshPool", ExecutorService::class.java)
                triggerRefresh(context)
                awaitLoads(loaderThreads(context), 2)
            }
        try {
            // 上下文（及运行时）已关闭，执行器仍由调用方管理
            pool.isShutdown.assert().isFalse()
            pool.submit { }.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }
}
