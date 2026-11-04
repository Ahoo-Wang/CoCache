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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

class CacheRuntimeTest {
    private fun spec(name: String, namespace: String = name) = cacheSpec<String, String>(name) {
        codec = StringCodec
        this.namespace = namespace
        externallySourced()
    }

    @Test
    fun createsCachesAndExposesThemByName() {
        cacheRuntime { backend = InMemoryBackend() }.use { runtime ->
            val cache = runtime.create(spec("user"))
            val set = runtime.create(setStoreSpec<String, String>("index") { elementCodec = StringCodec })
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
            // 生效的策略即创建时 spec 中的策略
            runtime.policy("user").assert().isSameAs(CachePolicy.DEFAULT)
            runtime.policy("index").assert().isSameAs(SetStoreSpec.DEFAULT_POLICY)
            runtime.policy("absent").assert().isNull()
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

    /**
     * 按 keyspace（前缀加命名空间）登记（RFC 0005 §4.7）：命名空间相同、前缀不同的两个缓存可以共存，key 互不重叠；
     * 前缀与命名空间都相同时失败，失败的创建不占用 keyspace。
     */
    @Test
    fun registersByKeyspaceSoTheSameNamespaceUnderAnotherPrefixCoexists() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val ours = runtime.create(spec("ours", "shared"))
            val theirs = runtime.create(
                cacheSpec<String, String>("theirs") {
                    codec = StringCodec
                    namespace = "shared"
                    keyPrefix = "other:"
                    externallySourced()
                }
            )
            ours.put("k", "ours")
            theirs.put("k", "theirs")
            ours["k"].assert().isEqualTo("ours")
            theirs["k"].assert().isEqualTo("theirs")
            requireNotNull(runtime.admin("ours")).inspect("k").storageKey.assert().isEqualTo("cocache:shared:k")
            requireNotNull(runtime.admin("theirs")).inspect("k").storageKey.assert().isEqualTo("other:shared:k")
            val error = runCatching { runtime.create(spec("third", "shared")) }.exceptionOrNull()
            error.assert().isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Namespace[shared] with keyPrefix[cocache:] is already used by another cache.")
            runtime.cache("third").assert().isNull()
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
                override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription {
                    subscriptions.incrementAndGet()
                    return Subscription { throw IllegalStateException("unsubscribe failed") }
                }

                override suspend fun publish(keyspace: Keyspace, frame: ByteArray) = delegate.channel.publish(
                    keyspace,
                    frame
                )
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
                override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription {
                    if (attempts.incrementAndGet() == 1) {
                        throw IllegalStateException("subscribe failed")
                    }
                    return delegate.channel.subscribe(keyspace, listener)
                }

                override suspend fun publish(keyspace: Keyspace, frame: ByteArray) = delegate.channel.publish(
                    keyspace,
                    frame
                )
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
            override fun store(keyspace: Keyspace): L1Store {
                check(attempts.incrementAndGet() > 1) { "store failed" }
                return delegate.store(keyspace)
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
     * 构造引擎失败时同样释放命名空间（RFC 0005 附录 A.L9）。存储没有 `close`，资源属于后端。
     */
    @Test
    fun failedEngineCreationReleasesTheNamespace() {
        val delegate = InMemoryBackend()
        val failFormat = java.util.concurrent.atomic.AtomicBoolean(true)
        val flaky = object : me.ahoo.cocache.api.spi.CoherenceBackend by delegate {
            // 引擎构造时读取后端的格式版本
            override val format: Int
                get() = if (failFormat.get()) throw IllegalStateException("format unavailable") else delegate.format
        }
        CacheRuntime.builder().backend(flaky).build().use { runtime ->
            runCatching { runtime.create(spec("user")) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java).hasMessage("format unavailable")
            runtime.cache("user").assert().isNull()
            failFormat.set(false)
            runtime.create(spec("user")).assert().isNotNull()
            runtime.cacheNames.assert().containsExactly("user")
        }
    }

    /**
     * 创建时检查缓存需要的 L1 能力（RFC 0005 §4.3）：`VersionedCache` 与 `VersionedStore` 要求 `versioned()`，`SetStore` 要求 `sets()`，
     * `Store` 要求 `conditional()`。缺失时以 [UnsupportedOperationException] 失败并指明缺少的能力，不注册任何东西：
     * 同名、同命名空间之后可以创建不需要它的缓存。
     */
    @Test
    fun missingCapabilitiesFailCreationAndRegisterNothing() {
        val backend = StrippedBackend(InMemoryBackend(), versioned = false, sets = false)
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            val versioned = runCatching {
                runtime.create(
                    versionedCacheSpec<String, String>("doc") {
                        codec = StringCodec
                        versionedLoader = me.ahoo.cocache.api.CacheLoader { null }
                    }
                )
            }.exceptionOrNull()
            versioned.assert().isInstanceOf(UnsupportedOperationException::class.java)
                .hasMessageContaining("Cache[doc] needs the L1 capabilities[versioned]")
                .hasMessageContaining("provides only [conditional]")
            val versionedStore = runCatching {
                runtime.create(versionedStoreSpec<String, String>("aggregate") { codec = StringCodec })
            }.exceptionOrNull()
            versionedStore.assert().isInstanceOf(UnsupportedOperationException::class.java)
                .hasMessageContaining("Cache[aggregate] needs the L1 capabilities[versioned]")
            val set = runCatching {
                runtime.create(setStoreSpec<String, String>("index") { elementCodec = StringCodec })
            }.exceptionOrNull()
            set.assert().isInstanceOf(UnsupportedOperationException::class.java)
                .hasMessageContaining("Cache[index] needs the L1 capabilities[sets]")
            runtime.cacheNames.assert().isEmpty()
            runtime.cache("doc").assert().isNull()
            runtime.create(spec("doc")).assert().isNotNull()
            runtime.create(spec("index")).assert().isNotNull()
            runtime.cacheNames.assert().containsExactlyInAnyOrder("doc", "index")
        }
        val withoutConditional = StrippedBackend(InMemoryBackend(), conditional = false)
        CacheRuntime.builder().backend(withoutConditional).build().use { runtime ->
            val store = runCatching { runtime.create(storeSpec<String, String>("policy") { codec = StringCodec }) }
                .exceptionOrNull()
            store.assert().isInstanceOf(UnsupportedOperationException::class.java)
                .hasMessageContaining("Cache[policy] needs the L1 capabilities[conditional]")
            runtime.cacheNames.assert().isEmpty()
        }
    }

    /**
     * spec 与返回类型一一对应（RFC 0005 §3.1）：五种 spec 各自创建对应的用户 API 类型，`create(Spec)` 按 spec 的类型分派。
     */
    @Test
    fun createReturnsTheTypeOfTheSpec() {
        cacheRuntime { backend = InMemoryBackend() }.use { runtime ->
            val specs: List<Spec<*, *>> = listOf(
                spec("cache"),
                versionedCacheSpec<String, String>("versioned-cache") {
                    codec = StringCodec
                    versionedLoader = me.ahoo.cocache.api.CacheLoader { null }
                },
                storeSpec<String, String>("store") { codec = StringCodec },
                versionedStoreSpec<String, String>("versioned-store") { codec = StringCodec },
                setStoreSpec<String, String>("set-store") { elementCodec = StringCodec },
            )
            val created = specs.map { runtime.create(it) }
            created[0].assert().isInstanceOf(me.ahoo.cocache.api.Cache::class.java)
            created[1].assert().isInstanceOf(me.ahoo.cocache.api.VersionedCache::class.java)
            created[2].assert().isInstanceOf(me.ahoo.cocache.api.Store::class.java)
            created[3].assert().isInstanceOf(me.ahoo.cocache.api.VersionedStore::class.java)
            created[4].assert().isInstanceOf(me.ahoo.cocache.api.SetStore::class.java)
            created.map { it.name }.assert().containsExactly(
                "cache",
                "versioned-cache",
                "store",
                "versioned-store",
                "set-store"
            )
            created.forEach { runtime.cache(it.name).assert().isSameAs(it) }
            // 每种类型各有自己的异步视图与挂起视图，每次返回同一个对象
            created.forEach { cache ->
                cache.async().assert().isSameAs(cache.async())
                cache.suspending().assert().isSameAs(cache.suspending())
                cache.async().sync().assert().isSameAs(cache)
                cache.suspending().async().assert().isSameAs(cache.async())
            }
        }
    }

    /**
     * 启动日志输出每个命名空间的 L1 能力（RFC 0005 §4.3）。
     */
    @Test
    fun creationLogsTheCapabilitiesOfTheNamespace() {
        val logger = LoggerFactory.getLogger(CacheRuntime::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            CacheRuntime.builder().backend(InMemoryBackend()).build().use {
                it.create(spec("full"))
                it.create(storeSpec<String, String>("store") { codec = StringCodec })
            }
            val stripped = StrippedBackend(InMemoryBackend(), conditional = false, versioned = false, sets = false)
            CacheRuntime.builder().backend(stripped).build().use { it.create(spec("bare", "bare-ns")) }
        } finally {
            logger.detachAppender(appender)
        }
        val created = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
            .filter { it.contains(" - created as ") }
        created.assert().hasSize(3)
        created[0].assert().startsWith("Cache[full] - created as Cache on namespace[full]")
            .endsWith("L1 capabilities[conditional, versioned, sets].")
        created[1].assert().startsWith("Cache[store] - created as Store on namespace[store]")
        created[2].assert().startsWith(
            "Cache[bare] - created as Cache on namespace[bare-ns]"
        ).endsWith("L1 capabilities[].")
    }
}
