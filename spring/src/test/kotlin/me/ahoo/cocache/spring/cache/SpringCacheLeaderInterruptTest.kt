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

package me.ahoo.cocache.spring.cache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.Forked
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * #871：`@Cacheable(sync = true)` 的方法体在 leader 的调用方线程上被中断（抛出 [InterruptedException]，中断标志已清除），
 * 只有 leader 失败；合并到这次加载的健康调用方按合并规则自己回源、成功（RFC 0005 §4.4），而不是收到 leader 的失败。
 * Spring 的 [SpringCache.ValueRetrievalException] 包装不能掩盖调用方线程的中断。
 */
class SpringCacheLeaderInterruptTest {
    @Test
    fun aDirectCacheFollowerLoadsItselfAfterTheLeaderIsInterrupted() = verify(Caller.DIRECT, restoreInterrupt = false)

    @Test
    fun aSpringCacheFollowerLoadsItselfAfterTheLeaderIsInterrupted() = verify(Caller.SPRING, restoreInterrupt = false)

    @Test
    fun aSpringCacheFollowerLoadsItselfIfTheMethodRestoresItsInterruptFlag() =
        verify(Caller.SPRING, restoreInterrupt = true)

    /**
     * 经 Spring 的缓存切面：方法体的异常先被 `CacheInterceptor` 包成 `CacheOperationInvoker.ThrowableWrapper` 再交给适配器，
     * 调用方最终收到方法体抛出的原始 [InterruptedException]。
     */
    @Test
    fun aCacheableFollowerLoadsItselfAfterTheLeaderIsInterrupted() = verify(Caller.CACHEABLE, restoreInterrupt = false)

    /**
     * 跟随者自己的线程被中断：只结束它自己的等待（RFC 0005 §4.4），以引擎的 [InterruptedException] 失败、中断标志恢复；
     * 适配器不包装引擎自己的中断（与直接调用核心相同），leader 照常完成。
     */
    @Test
    fun anInterruptedSpringCacheFollowerFailsWithItsOwnInterrupt() = verifyFollowerInterrupted(Caller.SPRING)

    @Test
    fun anInterruptedCacheableFollowerFailsWithItsOwnInterrupt() = verifyFollowerInterrupted(Caller.CACHEABLE)

    /**
     * 方法体的其它失败照常共享：经缓存切面时它同样被包成 `ThrowableWrapper`（原始异常不是中断），合并到这次加载的调用方
     * 收到同一个原始异常，不再回源。
     */
    @Test
    fun aCacheableFailureIsSharedWithTheOriginalException() = withCallers(Caller.CACHEABLE) { _, callers ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val failure = IllegalStateException("method failed")
        val leader = fork("failing-leader") {
            callers.get {
                calls.incrementAndGet()
                started.countDown()
                release.awaitOrFail("leader release")
                throw failure
            }
        }
        started.awaitOrFail("leader entered its method")
        val follower = fork("joined-follower") {
            callers.get { "unexpected follower value".also { calls.incrementAndGet() } }
        }
        awaitCondition("follower joined the flight") { follower.parked }
        release.countDown()
        leader.joinFailure().assert().isSameAs(failure)
        follower.joinFailure().assert().isSameAs(failure)
        calls.get().assert().isEqualTo(1)
    }

    private enum class Caller { DIRECT, SPRING, CACHEABLE }

    private fun verify(caller: Caller, restoreInterrupt: Boolean) = withCallers(caller) { cache, callers ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val leaderBody = Callable {
            calls.incrementAndGet()
            started.countDown()
            try {
                release.awaitOrFail("leader interruption")
            } catch (failure: InterruptedException) {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt()
                }
                throw failure
            }
            "unexpected leader value"
        }
        val followerBody = Callable {
            calls.incrementAndGet()
            "healthy value"
        }
        val leader = fork("interrupted-leader") { callers.get(leaderBody) }
        started.awaitOrFail("leader entered its method")
        val follower = fork("healthy-follower") { callers.get(followerBody) }
        try {
            awaitCondition("follower joined the flight") { follower.parked }
            calls.get().assert().isEqualTo(1)
            leader.thread.interrupt()
            verifyLeaderFailure(caller, leader)
            follower.join().assert().isEqualTo("healthy value")
            calls.get().assert().isEqualTo(2)
            follower.thread.isInterrupted.assert().isFalse()
            cache.get("k") { "unexpected re-load" }.assert().isEqualTo("healthy value")
        } finally {
            release.countDown()
        }
    }

    private fun verifyFollowerInterrupted(caller: Caller) = withCallers(caller) { cache, callers ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val leader = fork("healthy-leader") {
            callers.get {
                calls.incrementAndGet()
                started.countDown()
                release.awaitOrFail("leader release")
                "leader value"
            }
        }
        started.awaitOrFail("leader entered its method")
        val follower = fork("interrupted-follower") {
            runCatching { callers.get { "unexpected follower value".also { calls.incrementAndGet() } } }
                .exceptionOrNull() to Thread.currentThread().isInterrupted
        }
        try {
            awaitCondition("follower joined the flight") { follower.parked }
            follower.thread.interrupt()
            val (failure, interrupted) = follower.join()
            failure.assert().isInstanceOf(InterruptedException::class.java)
            interrupted.assert().isTrue()
        } finally {
            release.countDown()
        }
        leader.join().assert().isEqualTo("leader value")
        calls.get().assert().isEqualTo(1)
        cache.get("k") { "unexpected re-load" }.assert().isEqualTo("leader value")
    }

    private fun withCallers(caller: Caller, block: (Cache<String, String>, Callers) -> Unit) {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val cache = runtime.create(
                cacheSpec<String, String>(NAME) {
                    codec = StringCodec
                    externallySourced()
                }
            )
            Callers(runtime, cache, caller).use { block(cache, it) }
        }
    }

    private fun verifyLeaderFailure(caller: Caller, leader: Forked<String?>) {
        val failure = leader.joinFailure()
        if (caller == Caller.SPRING) {
            failure.assert().isInstanceOf(SpringCache.ValueRetrievalException::class.java)
            failure.cause.assert().isInstanceOf(InterruptedException::class.java)
        } else {
            failure.assert().isInstanceOf(InterruptedException::class.java)
        }
    }

    /**
     * 三种调用方式：直接调用核心的 `get(key, loader)`、直接调用 Spring 适配器、经 Spring 缓存切面调用 `@Cacheable(sync = true)` 方法。
     */
    private class Callers(runtime: CacheRuntime, private val cache: Cache<String, String>, private val caller: Caller) :
        AutoCloseable {
        private val adapter = requireNotNull(CoCacheSpringCacheManager(runtime).getCache(NAME))
        private val context = if (caller == Caller.CACHEABLE) cacheableContext(runtime) else null
        private val method = context?.getBean(CachedMethod::class.java)

        fun get(body: Callable<String>): String? = when (caller) {
            Caller.DIRECT -> cache.get("k") { body.call() }
            Caller.SPRING -> adapter.get("k", body)
            Caller.CACHEABLE -> requireNotNull(method).get(body)
        }

        override fun close() {
            context?.close()
        }

        private fun cacheableContext(runtime: CacheRuntime) = AnnotationConfigApplicationContext().apply {
            registerBean(CacheManager::class.java, Supplier { CoCacheSpringCacheManager(runtime) })
            registerBean(CachedMethod::class.java, Supplier { CachedBody() })
            register(CachingConfiguration::class.java)
            refresh()
        }
    }

    /**
     * 被缓存的方法：方法体由调用方传入；缓存键是常量 `'k'`，与传入的方法体无关，leader 与跟随者因此合并到同一次加载。
     */
    interface CachedMethod {
        @Throws(InterruptedException::class)
        fun get(body: Callable<String>): String?
    }

    class CachedBody : CachedMethod {
        @Cacheable(cacheNames = [NAME], key = "'k'", sync = true)
        override fun get(body: Callable<String>): String? = body.call()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    class CachingConfiguration

    private companion object {
        const val NAME = "leader-interrupted"
    }
}
