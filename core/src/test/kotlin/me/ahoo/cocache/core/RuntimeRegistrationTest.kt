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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 缓存的注册与关闭的交接（#815）、创建中的缓存不对外可见（#830）、存储前缀重叠的 keyspace（#819）。
 */
class RuntimeRegistrationTest {
    /**
     * 可以在创建存储或订阅时停住的后端，并统计仍然有效的订阅。
     */
    private class GatedBackend(
        private val delegate: InMemoryBackend = InMemoryBackend()
    ) : CoherenceBackend by delegate {
        val storeReached = CountDownLatch(1)
        val subscribeReached = CountDownLatch(1)

        @Volatile
        var subscribeGate = CountDownLatch(0)

        @Volatile
        var failSubscribe = false

        /**
         * 创建存储或订阅时抛出的 `Error`（如未实现的 SPI 方法中的 `TODO()`），抛出一次后清除。
         */
        @Volatile
        var storeError: Error? = null

        @Volatile
        var subscribeError: Error? = null
        val active = AtomicInteger()

        @Volatile
        private var storeGate = CountDownLatch(0)

        fun holdStore(): CountDownLatch = CountDownLatch(1).also { storeGate = it }

        override fun store(keyspace: Keyspace): L1Store {
            storeReached.countDown()
            storeGate.awaitOrFail("store gate")
            storeError?.let {
                storeError = null
                throw it
            }
            return delegate.store(keyspace)
        }

        override val channel: InvalidationChannel = object : InvalidationChannel {
            override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription {
                subscribeReached.countDown()
                subscribeGate.awaitOrFail("subscribe gate")
                check(!failSubscribe) { "subscribe failed" }
                subscribeError?.let {
                    subscribeError = null
                    throw it
                }
                val subscription = delegate.channel.subscribe(keyspace, listener)
                active.incrementAndGet()
                return Subscription {
                    active.decrementAndGet()
                    subscription.close()
                }
            }

            override suspend fun publish(keyspace: Keyspace, frame: ByteArray) = delegate.channel.publish(
                keyspace,
                frame
            )
        }
    }

    private fun spec(name: String, keyPrefix: String = "cocache:", namespace: String = name): StoreSpec<String, String> =
        storeSpec(name) {
            codec = StringCodec
            this.keyPrefix = keyPrefix
            this.namespace = namespace
        }

    /**
     * 创建停在创建存储时，[CacheRuntime.close] 完成：创建随后以 [IllegalStateException] 失败，不留下引擎或订阅。
     */
    @Test
    fun aCreationBlockedInStoreDoesNotOutliveClose() {
        val backend = GatedBackend()
        val release = backend.holdStore()
        val runtime = CacheRuntime.builder().backend(backend).build()
        val creating = fork { runtime.create(spec("user")) }
        backend.storeReached.awaitOrFail("store reached")
        runtime.close()
        release.countDown()
        creating.joinFailure().assert().isInstanceOf(
            IllegalStateException::class.java
        ).hasMessage("CacheRuntime is closed.")
        backend.active.get().assert().withFailMessage { "no subscription may outlive close" }.isZero()
        runtime.cacheNames.assert().isEmpty()
    }

    /**
     * 创建停在订阅时，[CacheRuntime.close] 完成：订阅随后返回，创建关闭它并失败；之后再关闭运行时也不会遗留它。
     */
    @Test
    fun aCreationBlockedInSubscribeDoesNotOutliveClose() {
        val backend = GatedBackend()
        val release = CountDownLatch(1).also { backend.subscribeGate = it }
        val runtime = CacheRuntime.builder().backend(backend).build()
        val creating = fork { runtime.create(spec("user")) }
        backend.subscribeReached.awaitOrFail("subscribe reached")
        runtime.close()
        release.countDown()
        creating.joinFailure().assert().isInstanceOf(
            IllegalStateException::class.java
        ).hasMessage("CacheRuntime is closed.")
        backend.active.get().assert().withFailMessage { "the late subscription is closed by the creation" }.isZero()
        runtime.close()
        backend.active.get().assert().isZero()
    }

    /**
     * 订阅确认之前，创建中的缓存不出现在公开查询中（[CacheRuntime.cacheNames]、[CacheRuntime.admin]、[CacheRuntime.cache]）；
     * 订阅失败而回滚之后同名重试，查询拿到的是新的引擎（#830：指标绑定按名称取得引擎）。同名的并发创建仍被拒绝。
     */
    @Test
    fun aCacheIsVisibleOnlyOnceItsSubscriptionIsConfirmed() {
        val backend = GatedBackend()
        val release = CountDownLatch(1).also { backend.subscribeGate = it }
        backend.failSubscribe = true
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            val creating = fork { runtime.create(spec("retry")) }
            backend.subscribeReached.awaitOrFail("subscribe reached")
            runtime.cacheNames.assert().isEmpty()
            runtime.admin("retry").assert().isNull()
            runtime.cache("retry").assert().isNull()
            runCatching { runtime.create(spec("retry", namespace = "other")) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage("Cache[retry] already exists.")
            release.countDown()
            creating.joinFailure().assert().isInstanceOf(IllegalStateException::class.java)

            backend.failSubscribe = false
            val store = runtime.create(spec("retry"))
            store["k"].assert().isNull()
            requireNotNull(runtime.admin("retry")).assert().isSameAs(store)
            requireNotNull(runtime.metrics("retry")).misses.assert().isEqualTo(1)
        }
    }

    /**
     * 缺陷复现：创建存储或订阅时 SPI 抛出 `Error`（未实现的方法中的 `TODO()`、类初始化失败）也回滚注册。原来只在 `Exception` 时回滚，
     * 缓存名与 keyspace 一直被占用、已创建的引擎不关闭，同名重试报“已存在”。`Error` 原样抛出。
     */
    @Test
    fun anErrorFromTheBackendRollsTheRegistrationBack() {
        val backend = GatedBackend()
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            val notImplemented = NotImplementedError("store is not implemented")
            backend.storeError = notImplemented
            runCatching { runtime.create(spec("user")) }.exceptionOrNull().assert().isSameAs(notImplemented)

            val initFailed = ExceptionInInitializerError("codec class failed to initialize")
            backend.subscribeError = initFailed
            runCatching { runtime.create(spec("user")) }.exceptionOrNull().assert().isSameAs(initFailed)
            runtime.cacheNames.assert().isEmpty()
            backend.active.get().assert().isZero()

            runtime.create(spec("user"))["k"].assert().isNull()
            runtime.cacheNames.assert().containsExactly("user")
        }
    }

    /**
     * 存储前缀（`{keyPrefix}{namespace}:`）相等或互为前缀的两个 keyspace 不能共存（#819）：否则一方的存储 key 落进另一方的范围，
     * 两个缓存相互覆盖，失效却只发往各自的频道。前缀不同、命名空间相同的缓存不重叠，照常共存；失败的创建不占用名字。
     */
    @Test
    fun keyspacesWhoseStorageKeysOverlapAreRejected() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            runtime.create(spec("first", keyPrefix = "app:", namespace = "users"))
            val nested = runCatching { runtime.create(spec("second", keyPrefix = "app:users:", namespace = "v2")) }
                .exceptionOrNull()
            nested.assert().isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(
                    "Cache[second] - its storage keys (app:users:v2:…) overlap those of Cache[first] (app:users:…)"
                )
            runCatching { runtime.create(spec("outer", keyPrefix = "", namespace = "app")) }.exceptionOrNull()
                .assert().withFailMessage { "app: contains app:users:" }.isInstanceOf(
                    IllegalArgumentException::class.java
                )
            runCatching { runtime.create(spec("same", keyPrefix = "app:u", namespace = "sers")) }.exceptionOrNull()
                .assert().withFailMessage { "app:u + sers is the storage prefix app:users: of Cache[first]" }
                .isInstanceOf(IllegalArgumentException::class.java)
            runtime.create(spec("users", keyPrefix = "cocache:", namespace = "users"))
            runtime.create(spec("sibling", keyPrefix = "app:", namespace = "orders"))
            runtime.cacheNames.assert().containsExactlyInAnyOrder("first", "users", "sibling")
            runtime.create(spec("second", keyPrefix = "other:", namespace = "v2")).name.assert().isEqualTo("second")
        }
    }
}
