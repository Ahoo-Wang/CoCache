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

import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class CacheRuntimeTest {
    private fun spec(name: String, namespace: String = name) = cacheSpec<String, String>(name) {
        codec = StringCodec
        this.namespace = namespace
    }

    @Test
    fun createsCachesAndExposesThemByName() {
        cacheRuntime { backend = InMemoryBackend() }.use { runtime ->
            val cache = runtime.create(spec("user"))
            val set = runtime.create(setCacheSpec<String, String>("index") { elementCodec = StringCodec })
            runtime.cache("user").assert().isSameAs(cache)
            runtime.cache("index").assert().isSameAs(set)
            runtime.cache("absent").assert().isNull()
            runtime.metrics("user").assert().isNotNull()
            runtime.metrics("absent").assert().isNull()
            val admin = requireNotNull(runtime.admin("user"))
            admin.name.assert().isEqualTo("user")
            cache.put("k", "v")
            cache["k"]
            admin.localSize.assert().isEqualTo(1)
            admin.clearLocal()
            admin.localSize.assert().isEqualTo(0)
            runtime.admin("absent").assert().isNull()
            runtime.cacheNames.assert().containsExactlyInAnyOrder("user", "index")
            cache.name.assert().isEqualTo("user")
            cache.toString().assert().contains("user")
        }
    }

    @Test
    fun rejectsDuplicateNamesAndNamespaces() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            runtime.create(spec("user"))
            runCatching {
                runtime.create(
                    spec("user", "other")
                )
            }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
            runCatching {
                runtime.create(
                    spec("profile", "user")
                )
            }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
            runtime.create(spec("profile")).name.assert().isEqualTo("profile")
        }
    }

    @Test
    fun requiresBackend() {
        runCatching { CacheRuntime.builder().build() }.exceptionOrNull().assert().isInstanceOf(
            IllegalArgumentException::class.java
        )
    }

    @Test
    fun usesGivenNodeIdAndClock() {
        val nodeId = NodeId.random()
        val clock = me.ahoo.cocache.core.clock.ManualClock(1000)
        CacheRuntime.builder().backend(InMemoryBackend()).nodeId(nodeId).clock(clock).build().use { runtime ->
            runtime.nodeId.assert().isEqualTo(nodeId)
            runtime.clock.assert().isSameAs(clock)
        }
    }

    @Test
    fun closeUnsubscribesAndIsIdempotent() {
        val backend = InMemoryBackend()
        val runtime = CacheRuntime.builder().backend(backend).build()
        runtime.create(spec("user"))
        runtime.close()
        runtime.close()
        runCatching {
            runtime.create(
                spec("other")
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun closeLeavesGivenRefreshExecutorRunning() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            CacheRuntime.builder().backend(InMemoryBackend()).refreshExecutor(executor).build().use { runtime ->
                runtime.create(spec("user"))
            }
            // 调用方提供的执行器由调用方管理：运行时关闭后仍可使用
            executor.isShutdown.assert().isFalse()
            executor.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun closeSurvivesFailingResources() {
        val subscriptions = AtomicInteger()
        val delegate = InMemoryBackend()
        val failing = object : me.ahoo.cocache.api.spi.CoherenceBackend by delegate {
            override val channel = object : me.ahoo.cocache.api.spi.InvalidationChannel {
                override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
                    subscriptions.incrementAndGet()
                    return Subscription { throw IllegalStateException("unsubscribe failed") }
                }

                override fun publish(namespace: String, frame: ByteArray) = delegate.channel.publish(namespace, frame)
            }
        }
        val runtime = CacheRuntime.builder().backend(failing).build()
        runtime.create(spec("user"))
        runtime.close()
        subscriptions.get().assert().isEqualTo(1)
    }

    @Test
    fun failedSubscribeRollsBackTheRegistration() {
        val delegate = InMemoryBackend()
        val attempts = AtomicInteger()
        val flaky = object : me.ahoo.cocache.api.spi.CoherenceBackend by delegate {
            override val channel = object : me.ahoo.cocache.api.spi.InvalidationChannel {
                override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
                    if (attempts.incrementAndGet() == 1) {
                        throw IllegalStateException("subscribe failed")
                    }
                    return delegate.channel.subscribe(namespace, listener)
                }

                override fun publish(namespace: String, frame: ByteArray) = delegate.channel.publish(namespace, frame)
            }
        }
        CacheRuntime.builder().backend(flaky).build().use { runtime ->
            runCatching {
                runtime.create(
                    spec("user")
                )
            }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            runtime.cache("user").assert().isNull()
            // 回滚后同名、同命名空间可以重新创建
            runtime.create(spec("user")).assert().isNotNull()
            runtime.cacheNames.assert().containsExactly("user")
        }
    }

    /**
     * 创建存储失败时释放命名空间（RFC 0005 附录 A.L9）：否则同名、同命名空间的重试一直以“命名空间已被占用”失败。
     */
    @Test
    fun failedStoreCreationReleasesTheNamespace() {
        val delegate = InMemoryBackend()
        val attempts = AtomicInteger()
        val flaky = object : me.ahoo.cocache.api.spi.CoherenceBackend by delegate {
            override fun store(namespace: String): me.ahoo.cocache.api.spi.RemoteStore {
                check(attempts.incrementAndGet() > 1) { "store failed" }
                return delegate.store(namespace)
            }
        }
        CacheRuntime.builder().backend(flaky).build().use { runtime ->
            runCatching { runtime.create(spec("user")) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java).hasMessage("store failed")
            runtime.cache("user").assert().isNull()
            runtime.create(spec("user")).assert().isNotNull()
            runtime.cacheNames.assert().containsExactly("user")
        }
    }

    /**
     * 构造引擎失败时同样释放命名空间，并关闭已创建的存储（RFC 0005 附录 A.L9）。
     */
    @Test
    fun failedEngineCreationReleasesTheNamespaceAndClosesTheStore() {
        val delegate = InMemoryBackend()
        val failFormat = java.util.concurrent.atomic.AtomicBoolean(true)
        val closedStores = AtomicInteger()
        val flaky = object : me.ahoo.cocache.api.spi.CoherenceBackend by delegate {
            // 引擎构造时读取后端的格式版本
            override val format: Int
                get() = if (failFormat.get()) throw IllegalStateException("format unavailable") else delegate.format

            override fun store(namespace: String): me.ahoo.cocache.api.spi.RemoteStore {
                val store = delegate.store(namespace)
                return object : me.ahoo.cocache.api.spi.RemoteStore by store {
                    override fun close() {
                        closedStores.incrementAndGet()
                        store.close()
                    }
                }
            }
        }
        CacheRuntime.builder().backend(flaky).build().use { runtime ->
            runCatching { runtime.create(spec("user")) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java).hasMessage("format unavailable")
            closedStores.get().assert().isEqualTo(1)
            runtime.cache("user").assert().isNull()
            failFormat.set(false)
            runtime.create(spec("user")).assert().isNotNull()
            runtime.cacheNames.assert().containsExactly("user")
        }
    }
}
