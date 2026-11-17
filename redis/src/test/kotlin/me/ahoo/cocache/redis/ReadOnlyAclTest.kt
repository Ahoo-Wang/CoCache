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

package me.ahoo.cocache.redis

import io.lettuce.core.ReadFrom
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只读的读取路径在真实 Redis 上按运维文档的 ACL 运行（wiki/operations/index.md#read-only）：
 * - 只有读权限（`%R~`）的用户按文档的那一行创建三种只读的权威缓存、读取、条件读取（含 `SCRIPT FLUSH` 之后的 `EVAL_RO`），
 *   收到另一个（写入的）用户发布的失效，订阅连接被强制重连之后仍收到；本地拒绝每个写入，L1 不变；`ACL LOG` 中没有它的条目。
 * - 同一个用户创建不是只读的缓存时与今天一样失败（发布探测被拒绝）。
 * - 缺少频道、key 的读权限或某条命令时，创建失败并指明缺少什么。
 * - 一个进程同时持有只读与读写的缓存（只读地读文档、写自己的报告）时，文档给出的那一行同样够用，`ACL LOG` 同样为空。
 * - 只读的读取只发往主节点。
 *
 * 每个用例建立唯一命名的 ACL 用户、结束时删除；`ACL LOG` 按用户名过滤（服务端由并行的测试共用，不清空）。
 */
class ReadOnlyAclTest {
    private val id = UUID.randomUUID().toString().substring(0, 8)
    private val namespace = "ro-$id"
    private val user = "cocache-reader-$id"
    private val clientName = "cocache-tck-reader-$id"

    /**
     * L2 副本活 10 分钟：只读节点读到新值只能是因为收到了失效帧（或订阅重置）。
     */
    private fun storeOf(name: String, readOnly: Boolean) = storeSpec<String, String>(name) {
        namespace = this@ReadOnlyAclTest.namespace
        codec = StringCodec
        this.readOnly = readOnly
        policy { localMaxAge = Duration.ofMinutes(10) }
    }

    /**
     * 心跳 200 ms：用例期间两种心跳（订阅连接与命令连接的 `PING`）都执行多次，它们的权限同样经 `ACL LOG` 检验。
     */
    private fun options(mode: RedisChannelMode, name: String = clientName) =
        RedisBackendOptions(clientName = name, channelMode = mode, heartbeat = Duration.ofMillis(200))

    private fun withReader(rules: List<String>, mode: RedisChannelMode, block: (RedisBackend) -> Unit) {
        try {
            RedisTestSupport.aclBackend(user, rules, options(mode)).use(block)
        } finally {
            RedisTestSupport.deleteAclUser(user)
        }
    }

    private fun assertAclLogEmpty() {
        RedisTestSupport.aclLog(
            user
        ).assert().withFailMessage { "ACL LOG of $user: ${RedisTestSupport.aclLog(user)}" }.isEmpty()
    }

    @ParameterizedTest
    @EnumSource(RedisChannelMode::class)
    fun readerWithTheDocumentedAclReadsAndHearsInvalidations(mode: RedisChannelMode) {
        val writerOptions = options(mode, "cocache-tck-writer-$id")
        RedisTestSupport.backend(writerOptions).use { writerBackend ->
            CacheRuntime.builder().backend(writerBackend).build().use { writerRuntime ->
                val docs = writerRuntime.create(storeOf("docs", readOnly = false))
                val versionedSpec = versionedStoreSpec<String, String>("versioned") {
                    namespace = "${this@ReadOnlyAclTest.namespace}-v"
                    codec = StringCodec
                }
                val setSpec = setStoreSpec<String, String>("members") {
                    namespace = "${this@ReadOnlyAclTest.namespace}-s"
                    elementCodec = StringCodec
                }
                val versioned = writerRuntime.create(versionedSpec)
                val members = writerRuntime.create(setSpec)
                docs.put("k", "v1")
                versioned.putIfNewer("k", "v", 3)
                members.add("k", listOf("a", "b"))

                withReader(readerRules(mode), mode) { readerBackend ->
                    CacheRuntime.builder().backend(readerBackend).build().use { reader ->
                        val store = reader.create(storeOf("docs", readOnly = true))
                        val versionedReader = reader.create(versionedSpec.toBuilder().readOnly().build())
                        val setReader = reader.create(setSpec.toBuilder().readOnly().build())
                        // 只有只读存储：原子广播从不探测（构造时也不探测）
                        readerBackend.atomicBroadcastIfProbed.assert()
                            .isEqualTo(if (mode == RedisChannelMode.SHARDED) false else null)

                        readsEverything(store, versionedReader.getVersioned("k"), setReader["k"])
                        // 脚本缓存丢失（故障切换、重启）：EVALSHA_RO 得到 NOSCRIPT，改用 EVAL_RO，只读的用户同样可以
                        RedisTestSupport.flushScripts()
                        requireNotNull(reader.admin("docs")).clearLocal()
                        readsEverything(store, versionedReader.getVersioned("k"), setReader["k"])

                        hearsWrites(docs, store, "v2")
                        refusesWrites(store, docs)
                        versionedReader.getVersioned("k").assert().isEqualTo(Versioned("v", 3))
                        runCatching { versionedReader.putIfNewer("k", "x", 9) }.exceptionOrNull()
                            .assert().isInstanceOf(UnsupportedOperationException::class.java)
                        runCatching { setReader.add("k", listOf("c")) }.exceptionOrNull()
                            .assert().isInstanceOf(UnsupportedOperationException::class.java)
                        versioned.getVersioned("k").assert().isEqualTo(Versioned("v", 3))
                        members["k"].assert().isEqualTo(setOf("a", "b"))
                        assertAclLogEmpty()

                        // Sharded 模式在 Redis Cluster 上：强制断开节点的订阅连接之后，这里看不到重新订阅的确认，以默认权限的用户同样如此
                        // （与只读无关，单独跟进）；其余组合都检验重连
                        if (mode != RedisChannelMode.SHARDED || !RedisTestSupport.isCluster) {
                            hearsWritesAfterAForcedReconnect(readerBackend, docs, store)
                        }
                        docs.delete("k")
                        awaitCondition("the reader hears the delete") { store["k"] == null }
                        readerBackend.heartbeatHealthy.assert().isTrue()
                        readerBackend.commandConnectionHealthy.assert().isTrue()
                        readerBackend.atomicBroadcastIfProbed.assert()
                            .isEqualTo(if (mode == RedisChannelMode.SHARDED) false else null)
                        assertAclLogEmpty()
                    }
                }
            }
        }
    }

    /**
     * 订阅连接被强制重连（Lettuce 重新握手并重新订阅，确认后重置订阅者）：之后仍收到失效。
     */
    private fun hearsWritesAfterAForcedReconnect(
        readerBackend: RedisBackend,
        writer: Store<String, String>,
        reader: Store<String, String>
    ) {
        val resets = AtomicInteger()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) = Unit

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        readerBackend.channel.subscribe(Keyspace("cocache:", namespace), listener).use {
            val before = resets.get()
            RedisTestSupport.killPubSubConnections(clientName)
            runCatching { awaitCondition("resubscribed after the forced reconnect") { resets.get() > before } }
                .onFailure {
                    throw AssertionError(
                        "${it.message} ACL LOG of $user: ${RedisTestSupport.aclLog(user)}",
                        it
                    )
                }
            hearsWrites(writer, reader, "v3")
        }
    }

    private fun readsEverything(store: Store<String, String>, versioned: Versioned<String>?, members: Set<String>?) {
        val current = requireNotNull(store["k"])
        store.getAll(listOf("k", "missing")).assert().isEqualTo(mapOf("k" to current))
        store.lookup("missing").assert().isInstanceOf(Lookup.Absent::class.java)
        (store.lookupRemote("k") as Lookup.Present).value.assert().isEqualTo(current)
        val present = store.getIfChanged("k", null) as ConditionalGet.Present
        present.value.assert().isEqualTo(current)
        store.getIfChanged("k", present.digest).assert().isEqualTo(ConditionalGet.Unchanged)
        store.getIfChanged("missing", null).assert().isEqualTo(ConditionalGet.Absent)
        store.async().get("k").join().assert().isEqualTo(current)
        versioned.assert().isEqualTo(Versioned("v", 3))
        members.assert().isEqualTo(setOf("a", "b"))
    }

    /**
     * 只读节点先把当前值读进 L2（副本活 10 分钟），写入方再写：只读节点读到新值只能是因为收到了失效帧。
     */
    private fun hearsWrites(writer: Store<String, String>, reader: Store<String, String>, value: String) {
        reader["k"].assert().isNotEqualTo(value)
        writer.put("k", value)
        awaitCondition("the reader hears the write of $value") { reader["k"] == value }
    }

    private fun refusesWrites(reader: Store<String, String>, writer: Store<String, String>) {
        val before = writer.lookupRemote("k")
        listOf<() -> Any?>(
            { reader.put("k", "x") },
            { reader.putAll(mapOf("k" to "x")) },
            { reader.delete("k") },
            { reader.putIfMatch("k", null, "x") },
            { reader.async().put("k", "x").join() },
        ).forEach { write ->
            val error = runCatching { write() }.exceptionOrNull()
            (error?.cause as? UnsupportedOperationException ?: error).assert()
                .isInstanceOf(UnsupportedOperationException::class.java)
        }
        writer.lookupRemote("k").assert().isEqualTo(before)
    }

    /**
     * 同一个只读用户创建不是只读的缓存：与今天一样在创建时失败（发布探测被拒绝），不登记任何东西。
     */
    @Test
    fun sameUserOnAReadWriteStoreFailsAsToday() {
        withReader(readerRules(RedisChannelMode.PUBSUB), RedisChannelMode.PUBSUB) { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val error = runCatching { runtime.create(storeOf("docs", readOnly = false)) }.exceptionOrNull()
                error.assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Redis denied publishing to the invalidation channel[cocache:inv:$namespace]")
                runtime.cache("docs").assert().isNull()
                // 原子广播的探测同样被拒绝（确定性的 ACL 错误），退回非原子广播：之后的实际频道探测被拒绝
                backend.atomicBroadcastIfProbed.assert().isEqualTo(false)
            }
        }
    }

    @Test
    fun missingChannelPermissionFailsCreationNamingThePattern() {
        val rules = readerRules(RedisChannelMode.PUBSUB).map { if (it == "&cocache:inv:*") "&other:inv:*" else it }
        withReader(rules, RedisChannelMode.PUBSUB) { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val error = runCatching { runtime.create(storeOf("docs", readOnly = true)) }.exceptionOrNull()
                error.assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining(
                        "Redis denied subscribing to the invalidation channel[cocache:inv:$namespace]"
                    )
                    .hasMessageContaining("[&cocache:inv:*]")
                runtime.cache("docs").assert().isNull()
            }
        }
    }

    @Test
    fun missingKeyReadPermissionFailsCreationNamingThePattern() {
        val rules = readerRules(RedisChannelMode.PUBSUB).map { if (it == "%R~cocache:*") "%R~other:*" else it }
        assertProbeRefused(rules, "allow the key pattern [%R~cocache:$namespace:*]")
    }

    @Test
    fun commandDeniedInsideTheScriptIsNamed() {
        assertProbeRefused(
            readerRules(RedisChannelMode.PUBSUB) - "+hkeys",
            "Redis denied the command [hkeys]: allow [+hkeys]"
        )
    }

    @Test
    fun missingReadOnlyScriptCommandIsNamed() {
        assertProbeRefused(
            readerRules(RedisChannelMode.PUBSUB) - "+evalsha_ro",
            "Redis denied the command [evalsha_ro]"
        )
    }

    @Test
    fun missingEvalRoIsNamedAfterNoscript() {
        RedisTestSupport.flushScripts()
        assertProbeRefused(readerRules(RedisChannelMode.PUBSUB) - "+eval_ro", "Redis denied the command [eval_ro]")
    }

    private fun assertProbeRefused(rules: List<String>, expected: String) {
        withReader(rules, RedisChannelMode.PUBSUB) { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val error = runCatching { runtime.create(storeOf("docs", readOnly = true)) }.exceptionOrNull()
                error.assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Read-only store of Keyspace(keyPrefix='cocache:', namespace='$namespace')")
                    .hasMessageContaining(expected)
                    .hasMessageContaining("Redis replied: ")
                runtime.cache("docs").assert().isNull()
            }
        }
    }

    /**
     * 一个进程同时持有只读与读写的缓存（只读地读文档、写自己的报告）：用户对文档只有 `%R~`，对报告有 `~`，并有发布探测要的权限。
     * 文档给出的那一行（[MIXED_LINE]）够用：两种缓存都能创建与使用，原子广播只探测一次，`ACL LOG` 为空。
     */
    @Test
    fun oneProcessWithReadOnlyAndReadWriteStores() {
        val docsNamespace = "docs-$id"
        val reportsNamespace = "reports-$id"
        val mixed = MIXED_LINE.replace(
            "app:docs:",
            "app:$docsNamespace:"
        ).replace("app:reports:", "app:$reportsNamespace:")
        val rules = rulesOf(mixed) + if (RedisTestSupport.isCluster) CLUSTER_RULES else emptyList()
        val docsSpec = storeSpec<String, String>("docs") {
            keyPrefix = "app:"
            namespace = docsNamespace
            codec = StringCodec
            policy { localMaxAge = Duration.ofMinutes(10) }
        }
        val reportsSpec = storeSpec<String, String>("reports") {
            keyPrefix = "app:"
            namespace = reportsNamespace
            codec = StringCodec
        }
        RedisTestSupport.backend(options(RedisChannelMode.PUBSUB, "cocache-tck-writer-$id")).use { writerBackend ->
            CacheRuntime.builder().backend(writerBackend).build().use { writerRuntime ->
                val docs = writerRuntime.create(docsSpec)
                docs.put("k", "v1")
                withReader(rules, RedisChannelMode.PUBSUB) { backend ->
                    CacheRuntime.builder().backend(backend).build().use { runtime ->
                        val readOnlyDocs = runtime.create(docsSpec.toBuilder().readOnly().build())
                        backend.atomicBroadcastIfProbed.assert().isNull()
                        val reports = runtime.create(reportsSpec)
                        backend.atomicBroadcastIfProbed.assert().isEqualTo(true)
                        readOnlyDocs["k"].assert().isEqualTo("v1")
                        reports.put("r", "ok")
                        reports["r"].assert().isEqualTo("ok")
                        hearsWrites(docs, readOnlyDocs, "v2")
                        runCatching { readOnlyDocs.put("k", "x") }.exceptionOrNull()
                            .assert().isInstanceOf(UnsupportedOperationException::class.java)
                        reports.delete("r")
                        assertAclLogEmpty()
                    }
                }
                docs.delete("k")
            }
        }
    }

    /**
     * 只读的读取只发往主节点（I-12）：集群连接的 `ReadFrom` 是 Lettuce 的默认值 `UPSTREAM`，`EVALSHA_RO` 的调用只计在主节点上
     * （CI 的集群每个主节点有一个从节点）。单机连接只连 URI 指定的节点，它是主节点。
     */
    @Test
    fun readOnlyReadsGoToThePrimary() {
        withReader(readerRules(RedisChannelMode.PUBSUB), RedisChannelMode.PUBSUB) { backend ->
            if (RedisTestSupport.isCluster) {
                backend.readFrom.assert().isEqualTo(ReadFrom.UPSTREAM)
            } else {
                backend.readFrom.assert().isNull()
            }
            RedisTestSupport.backend(options(RedisChannelMode.PUBSUB, "cocache-tck-writer-$id")).use { writerBackend ->
                CacheRuntime.builder().backend(writerBackend).build().use { writerRuntime ->
                    val docs = writerRuntime.create(storeOf("docs", readOnly = false))
                    (0 until 20).forEach { docs.put("k$it", "v$it") }
                    CacheRuntime.builder().backend(backend).build().use { runtime ->
                        val store = runtime.create(storeOf("docs", readOnly = true))
                        val before = RedisTestSupport.commandCallsByRole("evalsha_ro")
                        (0 until 20).forEach { store.lookupRemote("k$it") }
                        val after = RedisTestSupport.commandCallsByRole("evalsha_ro")
                        ((after["master"] ?: 0) - (before["master"] ?: 0)).assert().isGreaterThanOrEqualTo(20)
                        ((after["slave"] ?: 0) - (before["slave"] ?: 0)).assert().isZero()
                    }
                    (0 until 20).forEach { docs.delete("k$it") }
                }
            }
        }
    }

    /**
     * Redis 7.0 之前没有只读脚本：创建只读缓存时失败，并说明需要 Redis 7.0+（版本从探测自己的回复得知，不需要 `INFO` 等别的命令）。
     * CI 没有 Redis 6；本地以 `COCACHE_TEST_REDIS6_PORT` 指向一个 Redis 6.2 时运行（无 ACL，默认用户）。
     */
    @Test
    fun redisSixRefusesReadOnlyCachesAtCreation() {
        val port = System.getenv("COCACHE_TEST_REDIS6_PORT")?.toInt()
        org.junit.jupiter.api.Assumptions.assumeTrue(port != null, "COCACHE_TEST_REDIS6_PORT not set")
        RedisBackend.standalone(
            io.lettuce.core.RedisURI.create("localhost", requireNotNull(port)),
            options(RedisChannelMode.PUBSUB)
        )
            .use { backend ->
                CacheRuntime.builder().backend(backend).build().use { runtime ->
                    val error = runCatching { runtime.create(storeOf("docs", readOnly = true)) }.exceptionOrNull()
                    error.assert().isInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("read-only caches need Redis 7.0 or later")
                        .hasMessageContaining("Redis replied: ERR unknown command `EVALSHA_RO`")
                    runtime.cache("docs").assert().isNull()
                }
            }
    }

    /**
     * 运维页面（英文与中文）原样写出这里运行的两行 ACL。
     */
    @Test
    fun theOperationsPageShowsTheLinesThisTestRuns() {
        listOf("../wiki/operations/index.md", "../wiki/zh/operations/index.md").forEach { path ->
            val page = File(path).readText()
            page.assert().contains(READER_LINE)
            page.assert().contains(MIXED_LINE)
        }
    }

    companion object {
        /**
         * 运维文档的只读用户（`pubsub` 模式、默认前缀）。
         */
        const val READER_LINE = "ACL SETUSER cocache-reader on >change-me resetkeys resetchannels -@all %R~cocache:* " +
            "&cocache:inv:* +evalsha_ro +eval_ro +pttl +type +get +getrange +strlen +hkeys +hmget +subscribe +unsubscribe " +
            "+ping +info +client|setname +client|setinfo"

        /**
         * 运维文档的混合用户：只读地读 `app:docs:*`，读写 `app:reports:*`，并有发布探测要的权限。
         */
        const val MIXED_LINE = "ACL SETUSER app on >change-me resetkeys resetchannels -@all %R~app:docs:* ~app:reports:* " +
            "~cocache:!probe:* &app:inv:* &cocache:!probe:* +evalsha +eval +evalsha_ro +eval_ro +publish +subscribe " +
            "+unsubscribe +ping +info +client|setname +client|setinfo +get +getrange +strlen +set +del +pttl " +
            "+type +hgetall +hkeys +hmget +hset +hdel +hlen +pexpire +persist +setrange +time"

        /**
         * `ACL SETUSER <user> on >password` 之后的规则。
         */
        fun rulesOf(line: String): List<String> = line.split(" ").drop(4)

        /**
         * [READER_LINE] 的规则；Sharded 模式以 `SSUBSCRIBE` / `SUNSUBSCRIBE` 代替经典订阅，集群另需 Lettuce 发现拓扑的命令。
         */
        fun readerRules(mode: RedisChannelMode): List<String> {
            val rules = rulesOf(READER_LINE).map {
                when {
                    mode == RedisChannelMode.SHARDED && it == "+subscribe" -> "+ssubscribe"
                    mode == RedisChannelMode.SHARDED && it == "+unsubscribe" -> "+sunsubscribe"
                    else -> it
                }
            }
            return if (RedisTestSupport.isCluster) rules + CLUSTER_RULES else rules
        }

        /**
         * 集群中 Lettuce 发现与刷新拓扑用到的命令（运维文档）。
         */
        val CLUSTER_RULES = listOf("+cluster|nodes", "+cluster|myid")
    }
}
