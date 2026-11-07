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
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.test.Concurrency
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
 * `cocache.refresh-executor`、`cocache.executor`：按 bean 名解析、实际被使用、名称错误时启动失败。
 */
class ExecutorPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(CoCacheAutoConfiguration::class.java, CoCacheSpringCacheAutoConfiguration::class.java)
        )
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
                loaderThreads.add(Concurrency.currentThreadName())
                "user-$it"
            }
            policy = DefaultPolicies.CACHE.toBuilder()
                .ttl(Duration.ofMinutes(10))
                .ttlJitter(Duration.ZERO)
                .localMaxAge(Duration.ofMinutes(10))
                .refreshWindow(Duration.ofMinutes(1))
                .build()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ExecutorConfig {
        @Bean
        fun refreshRecorder(): RecordingExecutor = RecordingExecutor()

        @Bean
        fun executorRecorder(): RecordingExecutor = RecordingExecutor()

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
        ).withUserConfiguration(ExecutorConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.refreshExecutor.assert().isEqualTo("refreshRecorder")
        }
        runner.run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.refreshExecutor.assert().isNull()
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
                loaderThreads(context).toList().assert().containsExactly(
                    Concurrency.currentThreadName(),
                    Concurrency.currentThreadName()
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
    fun retrieveUsesTheAsyncView() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val cache = requireNotNull(context.getBean(CacheManager::class.java).getCache("user"))
            retrieveValue(cache, "1").assert().isEqualTo("user-1")
            // 同步数据源在运行时的执行器上执行：默认每个任务一个虚拟线程（`cocache-worker-*`）
            val thread = loaderThreads(context).single()
            thread.assert().withFailMessage { "the loader ran on $thread" }.startsWith("cocache-worker-")
            // L2 命中：返回已完成的 future，不切换线程
            requireNotNull(cache.retrieve("1")).isDone.assert().isTrue()
        }
    }

    @Test
    fun executorBeanIsUsed() {
        runner.withPropertyValues("cocache.executor=executorRecorder")
            .withUserConfiguration(ExecutorConfig::class.java).run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(CoCacheProperties::class.java).executor.assert().isEqualTo("executorRecorder")
                val recorder = context.getBean("executorRecorder", RecordingExecutor::class.java)
                context.getBean(me.ahoo.cocache.core.CacheRuntime::class.java).executor.assert().isSameAs(recorder)
                userCache(context).async().get("1").get(5, TimeUnit.SECONDS).assert().isEqualTo("user-1")
                // 记录执行器就地执行：同步数据源经运行时的执行器在调用方线程上执行
                recorder.tasks.get().assert().isGreaterThanOrEqualTo(1)
                loaderThreads(context).single().assert().isEqualTo(Concurrency.currentThreadName())
            }
    }

    @Test
    fun springBootApplicationTaskExecutorCanBeUsed() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java,
                    CoCacheSpringCacheAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues(
                "cocache.backend=memory",
                "cocache.executor=applicationTaskExecutor",
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
            "cocache.executor" to "missingExecutor",
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
        listOf("cocache.refresh-executor", "cocache.executor").forEach { property ->
            runner.withPropertyValues("$property=notAnExecutor")
                .withUserConfiguration(ExecutorConfig::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    context.startupFailure.assert()
                        .hasStackTraceContaining("$property=notAnExecutor")
                        .hasStackTraceContaining("java.lang.StringBuilder, not a java.util.concurrent.Executor")
                }
        }
    }

    /**
     * 销毁时记录运行时是否已经关闭（关闭后 `create` 抛出 IllegalStateException）。
     */
    class OrderedExecutor(private val name: String, private val runtimeClosedAtDestroy: MutableMap<String, Boolean>) :
        Executor,
        org.springframework.beans.factory.DisposableBean {
        lateinit var runtime: me.ahoo.cocache.core.CacheRuntime

        override fun execute(command: Runnable) = command.run()

        override fun destroy() {
            val probe = cacheSpec<String, String>("probe-$name") {
                codec = StringCodec
                externallySourced()
            }
            runtimeClosedAtDestroy[name] = runCatching { runtime.create(probe) }.exceptionOrNull() is IllegalStateException
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OrderedExecutorConfig {
        @Bean
        fun runtimeClosedAtDestroy(): MutableMap<String, Boolean> = java.util.concurrent.ConcurrentHashMap()

        @Bean
        fun orderedRefresh(runtimeClosedAtDestroy: MutableMap<String, Boolean>) = OrderedExecutor(
            "refresh",
            runtimeClosedAtDestroy
        )

        @Bean
        fun orderedExecutor(runtimeClosedAtDestroy: MutableMap<String, Boolean>) = OrderedExecutor(
            "executor",
            runtimeClosedAtDestroy
        )
    }

    @Test
    fun userExecutorsAreDestroyedAfterTheRuntime() {
        lateinit var closedAtDestroy: Map<String, Boolean>
        runner.withPropertyValues(
            "cocache.refresh-executor=orderedRefresh",
            "cocache.executor=orderedExecutor",
        ).withUserConfiguration(OrderedExecutorConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(me.ahoo.cocache.core.CacheRuntime::class.java)
            listOf("orderedRefresh", "orderedExecutor").forEach { name ->
                context.beanFactory.getDependentBeans(
                    name
                ).toList().assert().contains(CoCacheAutoConfiguration.RUNTIME_BEAN)
                context.getBean(name, OrderedExecutor::class.java).runtime = runtime
            }
            @Suppress("UNCHECKED_CAST")
            closedAtDestroy = context.getBean("runtimeClosedAtDestroy") as Map<String, Boolean>
        }
        // 容器关闭时先关闭运行时，再销毁它使用的执行器
        closedAtDestroy.assert().isEqualTo(mapOf("refresh" to true, "executor" to true))
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
