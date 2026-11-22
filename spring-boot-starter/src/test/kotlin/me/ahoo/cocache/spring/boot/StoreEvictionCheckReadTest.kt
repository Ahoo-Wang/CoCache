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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.L1RejectedException
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.StoreEvictionRisk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * 驱逐检查读取策略的重试与失败信息（#1009）：暂时的失败在启动与经 starter 创建时有界重试，都读不到时仍让启动失败，信息说出真实原因。
 */
class StoreEvictionCheckReadTest {
    private fun runtimeOn(backend: CoherenceBackend): CacheRuntime {
        val runtime = mockk<CacheRuntime>(relaxed = true)
        every { runtime.backend } returns backend
        return runtime
    }

    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(StoreEvictionCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun failure(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()

    /**
     * 依次抛出 [failures]、之后返回 [policies] 的 Redis 后端。
     */
    private fun redisFailing(
        vararg failures: RuntimeException,
        policies: Map<String, String> = mapOf(
            "redis" to "noeviction"
        )
    ): RedisBackend {
        val redis = redisBackendMock()
        val remaining = ArrayDeque(failures.toList())
        every { redis.readMaxmemoryPolicies() } answers { remaining.removeFirstOrNull()?.let { throw it } ?: policies }
        return redis
    }

    /**
     * 记下每次等待、不真的等待。
     */
    private class Pauses(private val interruptAt: Int = Int.MAX_VALUE) : (Duration) -> Boolean {
        val waits = mutableListOf<Duration>()

        override fun invoke(wait: Duration): Boolean {
            waits += wait
            return waits.size < interruptAt
        }
    }

    private fun checkWith(
        backend: CoherenceBackend,
        pauses: Pauses,
        mode: CoCacheProperties.EvictionCheck = CoCacheProperties.EvictionCheck.FAIL,
        assumed: String? = null,
        stores: Map<String, Duration?> = mapOf("index" to null),
    ) = StoreEvictionCheck(runtimeOn(backend), mode, assumed, null, pauses) { stores }

    /**
     * 缺陷复现（#1009）：启动时读策略超时（CPU 吃紧时客户端没在 `commandTimeout` 内处理应答）曾让启动立即失败。一次慢应答不再致命：
     * 暂时的失败在有界的次数内重试，读到安全的策略就照常启动。
     */
    @Test
    fun aSlowAnswerAtStartIsRetried() {
        val backend = redisFailing(timedOut(), L1RejectedException("Connection is in a disconnected state"))
        val pauses = Pauses()
        val check = checkWith(backend, pauses)
        val warned = warnings { check.afterSingletonsInstantiated() }
        check.verdict.assert().isEqualTo(StoreEvictionRisk.Safe(mapOf("redis" to "noeviction")))
        verify(exactly = 3) { backend.readMaxmemoryPolicies() }
        pauses.waits.assert().containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1))
        warned.size.assert().isEqualTo(2)
        warned.first().assert().contains(
            "attempt 1 of ${StoreEvictionCheck.STARTUP_ATTEMPTS}"
        ).contains("Command timed out")
    }

    /**
     * 都读不到时仍按读不到判断（fail closed）：启动失败，信息说出是超时，以它为 cause，不建议授予 `+info`（#1009）。
     */
    @Test
    fun startupFailsClosedWithTheTimeoutAsItsCause() {
        val timeouts = List(StoreEvictionCheck.STARTUP_ATTEMPTS) { timedOut() }
        val backend = redisFailing(*timeouts.toTypedArray())
        val pauses = Pauses()
        CoCacheProperties.EvictionCheck.entries.forEach { mode ->
            pauses.waits.clear()
            val failed = failure {
                checkWith(
                    redisFailing(*timeouts.toTypedArray()),
                    pauses,
                    mode
                ).afterSingletonsInstantiated()
            }
            failed.assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("[index]")
                .hasMessageContaining("did not answer in ${StoreEvictionCheck.STARTUP_ATTEMPTS} attempts")
                .hasMessageContaining("Command timed out")
                .hasMessageContaining("not a missing permission")
                .hasMessageContaining("assume-maxmemory-policy")
                .hasMessageNotContaining("+info")
            requireNotNull(failed).cause.assert().isSameAs(timeouts.last())
            pauses.waits.assert().containsExactly(
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
            )
        }
        // 配置了假定值时，重试之后按假定值判断
        warnings { checkWith(backend, pauses, assumed = "noeviction").afterSingletonsInstantiated() }
            .filterNot { it.contains("attempt") }.assert().isEmpty()
        verify(exactly = StoreEvictionCheck.STARTUP_ATTEMPTS) { backend.readMaxmemoryPolicies() }
    }

    /**
     * Redis 拒绝 `INFO`（权限、命令被改名）重试也不会变：只读一次，信息建议授予 `+info`，以拒绝为 cause。
     */
    @Test
    fun aRefusedInfoIsNotRetried() {
        val refused = noPermission()
        val renamed = RedisCommandExecutionException("ERR unknown command 'INFO', with args beginning with: 'memory'")
        listOf(refused, renamed).forEach { cause ->
            val backend = redisFailing(cause)
            val pauses = Pauses()
            val failed = failure { checkWith(backend, pauses).afterSingletonsInstantiated() }
            failed.assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Redis refused INFO")
                .hasMessageContaining("+info")
                .hasMessageContaining("assume-maxmemory-policy")
            requireNotNull(failed).cause.assert().isSameAs(cause)
            verify(exactly = 1) { backend.readMaxmemoryPolicies() }
            pauses.waits.assert().isEmpty()
        }
    }

    /**
     * 暂时的状态（`LOADING` 等）重试；应答里没有策略这类确定的失败不重试，信息带上原因。
     */
    @Test
    fun onlyTransientFailuresAreRetried() {
        StoreEvictionCheck.transient(RedisCommandExecutionException("LOADING Redis is loading the dataset in memory"))
            .assert().isTrue()
        StoreEvictionCheck.transient(RedisCommandExecutionException("ERR something else")).assert().isFalse()
        StoreEvictionCheck.transient(RedisException("Command cancelled", java.util.concurrent.CancellationException()))
            .assert().isTrue()
        StoreEvictionCheck.transient(io.lettuce.core.RedisConnectionException("Unable to connect")).assert().isTrue()
        StoreEvictionCheck.transient(RedisException(java.io.IOException("Connection reset by peer"))).assert().isTrue()
        StoreEvictionCheck.transient(
            RedisException("Command timed out after PT0.1S.", java.util.concurrent.TimeoutException())
        )
            .assert().isTrue()
        StoreEvictionCheck.transient(RedisCommandExecutionException(null as String?)).assert().isFalse()

        val noField = RedisException("INFO memory returned no maxmemory_policy.")
        val backend = redisFailing(noField)
        val pauses = Pauses()
        val failed = failure { checkWith(backend, pauses).afterSingletonsInstantiated() }
        failed.assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("INFO memory failed")
            .hasMessageContaining("no maxmemory_policy")
            .hasMessageNotContaining("+info")
        requireNotNull(failed).cause.assert().isSameAs(noField)
        verify(exactly = 1) { backend.readMaxmemoryPolicies() }
    }

    /**
     * 等待被中断时不再重试：按已读的次数判断（读不到即失败），中断标志由等待保留。
     */
    @Test
    fun anInterruptedPauseStopsRetrying() {
        val backend = redisFailing(timedOut(), timedOut(), timedOut())
        val failed = failure { checkWith(backend, Pauses(interruptAt = 2)).afterSingletonsInstantiated() }
        failed.assert().isInstanceOf(
            IllegalStateException::class.java
        ).hasMessageContaining("did not answer in 2 attempts")
        verify(exactly = 2) { backend.readMaxmemoryPolicies() }
    }

    /**
     * 默认的等待：被中断时立即返回 `false` 并保留中断标志。
     */
    @Test
    fun theDefaultPauseKeepsTheInterruptFlag() {
        val backend = redisFailing(timedOut())
        val check = StoreEvictionCheck(
            runtimeOn(backend),
            CoCacheProperties.EvictionCheck.FAIL,
            null
        ) { mapOf("index" to null) }
        Thread.currentThread().interrupt()
        try {
            failure { check.afterSingletonsInstantiated() }.assert()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("did not answer in 1 attempt ")
            Thread.currentThread().isInterrupted.assert().isTrue()
        } finally {
            Thread.interrupted()
        }
        // 不被中断时真的等待，之后读到
        val slow = redisFailing(timedOut())
        StoreEvictionCheck(runtimeOn(slow), CoCacheProperties.EvictionCheck.FAIL, null) { mapOf("index" to null) }
            .afterSingletonsInstantiated()
        verify(exactly = 2) { slow.readMaxmemoryPolicies() }
    }

    @Test
    fun retryPauseDoublesUpToItsCap() {
        (1..6).map { StoreEvictionCheck.retryPause(it) }.assert().containsExactly(
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
        )
        StoreEvictionCheck.retryPause(Int.MAX_VALUE).assert().isEqualTo(Duration.ofSeconds(2))
    }

    /**
     * 启动之后经 starter 创建权威缓存时同样重试（致命的结论让创建失败）；周期重读只读一次（失败后下一个间隔再读）。
     */
    @Test
    fun theCreationPathRetriesAndTheRecheckDoesNot() {
        val redis = redisFailing()
        val memory = InMemoryBackend()
        val wrapped = object : CoherenceBackend by memory {
            override fun <T : Any> unwrap(type: Class<T>): T? =
                if (type == RedisBackend::class.java) type.cast(redis) else memory.unwrap(type)
        }
        CacheRuntime.builder().backend(wrapped).build().use { runtime ->
            val pauses = Pauses()
            val check = StoreEvictionCheck(
                runtime,
                CoCacheProperties.EvictionCheck.FAIL,
                null,
                null,
                pauses
            ) { emptyMap() }
            check.afterSingletonsInstantiated()
            verify(exactly = 0) { redis.readMaxmemoryPolicies() }

            val timeouts = ArrayDeque(listOf(timedOut(), timedOut()))
            every {
                redis.readMaxmemoryPolicies()
            } answers { timeouts.removeFirstOrNull()?.let { throw it } ?: mapOf("redis" to "noeviction") }
            check.check(storeSpec<String, String>("late") { codec = StringCodec })
            pauses.waits.size.assert().isEqualTo(2)
            check.verdict.assert().isInstanceOf(StoreEvictionRisk.Safe::class.java)

            runtime.create(storeSpec<String, String>("late") { codec = StringCodec })
            every { redis.readMaxmemoryPolicies() } throws timedOut()
            val warned = warnings { check.recheck() }
            warned.single().assert().contains("did not answer in 1 attempt ").contains("Command timed out")
            pauses.waits.size.assert().isEqualTo(2)
        }
    }

    /**
     * 各种失败的分类与信息：没有消息的错误应答、不是错误应答的 `NOPERM` 文本、裸的超时消息、Lettuce 自己的超时异常。
     */
    @Test
    fun failuresWithoutAUsefulMessageAreNeitherRefusedNorTransient() {
        StoreEvictionCheck.transient(
            io.lettuce.core.RedisCommandTimeoutException("Command timed out after 2 second(s)")
        )
            .assert().isTrue()
        StoreEvictionCheck.transient(RedisException("Command timed out after PT2S.")).assert().isTrue()
        StoreEvictionCheck.transient(RedisException(null as String?)).assert().isFalse()
        StoreEvictionCheck.transient(RedisException("NOPERM not a reply")).assert().isFalse()

        val risk = StoreEvictionRisk.Risk(
            StoreEvictionRisk.Kind.UNREADABLE_POLICY,
            setOf("index"),
            emptyMap(),
            fatal = true,
            "rule message."
        )
        listOf(
            RedisCommandExecutionException(null as String?),
            RedisCommandExecutionException("ERR other"),
            RedisException("NOPERM not a reply"),
        ).forEach { cause ->
            StoreEvictionCheck.unreadableMessage(risk, StoreEvictionCheck.ReadFailure(cause, 1)).assert()
                .contains("INFO memory failed")
                .doesNotContain("+info")
        }
        // 没有记下原因时用规则给出的消息
        StoreEvictionCheck.unreadableMessage(risk, null).assert()
            .isEqualTo("rule message. Set it with cocache.redis.assume-maxmemory-policy.")
    }
}
