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

import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisException
import io.lettuce.core.cluster.SlotHash
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 8.x 的频道布局（RFC 0005 §4.7）与按存储探测实际频道（K8）。三种广播方式：经典 Pub/Sub 的原子广播、经典 Pub/Sub 的
 * 非原子广播（ACL 不放行探测频道）、Sharded Pub/Sub；单机与 Cluster 都运行（Cluster 见 AGENTS.md 的命令）。
 *
 * 用到 ACL 的用例各自建立唯一命名的用户，结束时删除；不发送服务端范围的 `CLIENT PAUSE` 或 `CONFIG`。
 */
class RedisLayoutTest {
    private val namespace = "layout-" + UUID.randomUUID().toString().substring(0, 8)

    enum class Mode { ATOMIC, NON_ATOMIC, SHARDED }

    @Test
    fun channelNamesCarryTheKeyPrefix() {
        RedisInvalidationChannel.channelOf(Keyspace("cocache:", "user")).assert().isEqualTo("cocache:inv:user")
        RedisInvalidationChannel.channelOf(Keyspace("app:", "user")).assert().isEqualTo("app:inv:user")
        RedisInvalidationChannel.channelOf(Keyspace("", "user")).assert().isEqualTo("inv:user")
        RedisInvalidationChannel.channelPatternOf(Keyspace("cocache:", "user")).assert().isEqualTo("&cocache:inv:*")
        // 前缀为空时没有公共前缀：放行这一个频道
        RedisInvalidationChannel.channelPatternOf(Keyspace("", "user")).assert().isEqualTo("&inv:user")
        RedisBackend.PROBE_KEY_NAME.assert().isEqualTo("cocache:!probe:{a}")
        RedisBackend.PROBE_CHANNEL_NAME.assert().isEqualTo("cocache:!probe:{b}")
        // 两个 hash tag 落在不同的 slot：把 PUBLISH 当作 SPUBLISH 执行的环境因此报跨 slot 错误
        SlotHash.getSlot(
            RedisBackend.PROBE_KEY_NAME
        ).assert().isNotEqualTo(SlotHash.getSlot(RedisBackend.PROBE_CHANNEL_NAME))
    }

    /**
     * 按存储探测实际频道（K8）：只有 ACL 拒绝按配置错误处理（创建失败并指明要放行的频道模式），跨 slot 与暂时性错误不算。
     */
    @Test
    fun onlyPermissionErrorsCountAsAclDenial() {
        RedisBackend.isPermissionDenied(
            RedisException(
                "wrapper",
                RedisException("NOPERM User u has no permissions to access the 'app:inv:x' channel")
            )
        ).assert().isTrue()
        RedisBackend.isPermissionDenied(
            RedisException(
                "ERR The user executing the script can't publish to the channel mentioned in the command script: x"
            )
        ).assert().isTrue()
        RedisBackend.isPermissionDenied(RedisException("No permissions to access a key")).assert().isTrue()
        RedisBackend.isPermissionDenied(
            RedisException("CROSSSLOT Keys in request don't hash to the same slot")
        ).assert().isFalse()
        RedisBackend.isPermissionDenied(RedisCommandTimeoutException("Command timed out")).assert().isFalse()
    }

    /**
     * 每次 `store(keyspace)` 都向该 keyspace 的实际频道发布一个零 key 的帧（K8）：原子广播时经探测脚本，否则是普通的
     * `PUBLISH` / `SPUBLISH`。它是合法帧，不重置订阅者。
     */
    @ParameterizedTest
    @EnumSource(Mode::class)
    fun storeProbesTheRealChannelWithAZeroKeyFrame(mode: Mode) {
        val keyspace = Keyspace("app:", namespace)
        withBackend(mode, listOf("&app:inv:*")) { prober ->
            withBackend(mode, listOf("&app:inv:*")) { subscriber ->
                prober.atomicBroadcast.assert().isEqualTo(mode == Mode.ATOMIC)
                val received = CopyOnWriteArrayList<Pair<List<String>, NodeId>>()
                val resets = AtomicInteger()
                val listener = object : InvalidationListener {
                    override fun onInvalidated(keys: List<String>, origin: NodeId) {
                        received.add(keys to origin)
                    }

                    override fun onReset() {
                        resets.incrementAndGet()
                    }
                }
                subscriber.channel.subscribe(keyspace, listener).use {
                    awaitCondition("reset on subscribe") { resets.get() >= 1 }
                    prober.store(keyspace)
                    awaitCondition("zero-key frame") { received.isNotEmpty() }
                    received.single().first.assert().isEmpty()
                    received.single().second.assert().isEqualTo(NodeId(ByteArray(NodeId.SIZE)))
                    resets.get().assert().isEqualTo(1)
                }
            }
        }
    }

    /**
     * ACL 不放行实际频道时 `runtime.create` 失败（K8）：指明要放行的频道模式，不降级为非原子广播，不登记任何东西。
     * 原子广播的用例放行了探测频道与默认前缀的频道：构造时的探测通过，只有前缀 `app:` 的实际频道被拒绝。
     */
    @ParameterizedTest
    @EnumSource(Mode::class)
    fun aclDenyingTheRealChannelFailsCreate(mode: Mode) {
        withBackend(mode, listOf("&cocache:inv:*")) { backend ->
            backend.atomicBroadcast.assert().isEqualTo(mode == Mode.ATOMIC)
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val denied = "denied-$namespace"
                val error = runCatching { runtime.create(spec(denied, "app:")) }.exceptionOrNull()
                error.assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("invalidation channel[app:inv:$namespace]")
                    .hasMessageContaining("channel pattern [&app:inv:*]")
                error?.cause.assert().isInstanceOf(RedisException::class.java)
                runtime.cache(denied).assert().isNull()
                // 同一命名空间在放行的前缀下照常创建
                val allowed = runtime.create(spec("allowed-$namespace", "cocache:"))
                allowed["k"].assert().isEqualTo("v-k")
                allowed.put("k", "v")
                allowed["k"].assert().isEqualTo("v")
            }
        }
    }

    /**
     * 频道名按文档构造（`{keyPrefix}inv:{namespace}`）：不经后端、直接向这个名字发布的帧送达缓存，前缀为空时同样。
     * 经典与 Sharded 模式都按这个名字，Sharded 模式下 `SPUBLISH` 按它的 slot 路由到 Cluster 中的所属节点。
     */
    @ParameterizedTest
    @CsvSource("ATOMIC, app:", "ATOMIC, ''", "SHARDED, app:", "SHARDED, ''")
    fun framesPublishedToTheDocumentedChannelReachTheCache(mode: Mode, keyPrefix: String) {
        val storageKey = "${keyPrefix}$namespace:k"
        try {
            withBackend(mode, emptyList()) { backend ->
                CacheRuntime.builder().backend(backend).build().use { runtime ->
                    val name = "documented-$namespace"
                    val cache = runtime.create(spec(name, keyPrefix))
                    cache["k"].assert().isEqualTo("v-k")
                    RedisTestSupport.withCommands { it.exists(storageKey) }.assert().isEqualTo(1L)
                    val admin = requireNotNull(runtime.admin(name))
                    admin.inspect("k").local.assert().isNotNull()
                    // 全 ASCII 的帧：按字符串发布时字节原样保留
                    val frame = String(
                        InvalidationFrame.encode(NodeId("0123456789abcdef".toByteArray()), listOf(storageKey))
                    )
                    val channel = "${keyPrefix}inv:$namespace"
                    RedisTestSupport.withCommands {
                        if (mode == Mode.SHARDED) {
                            it.spublish(
                                channel,
                                frame
                            )
                        } else {
                            it.publish(channel, frame)
                        }
                    }
                    awaitCondition(
                        "frame received"
                    ) { requireNotNull(runtime.metrics(name)).invalidationsReceived >= 1 }
                    admin.inspect("k").local.assert().isNull()
                }
            }
        } finally {
            RedisTestSupport.withCommands { it.del(storageKey) }
        }
    }

    /**
     * 前缀为空时 key 没有公共前缀，ACL 按命名空间列出：`~{namespace}:*` 与 `&inv:{namespace}`，内部 key 为 `~!*`（运维文档）；
     * 经典 Pub/Sub 的原子广播探测另需 `~cocache:!probe:*` 与 `&cocache:!probe:*`。在这样的用户下探测通过，原子广播开启，
     * 读写与跨节点失效照常；没有列出的命名空间创建失败，信息给出它自己的频道。
     */
    @Test
    fun emptyKeyPrefixRunsUnderTheDocumentedAcl() {
        val user = "cocache-layout-" + UUID.randomUUID()
        val rules = listOf(
            "resetkeys",
            "~$namespace:*",
            "~!*",
            "~cocache:!probe:*",
            "resetchannels",
            "&inv:$namespace",
            "&cocache:!probe:*",
            "+@all"
        )
        val name = "empty-$namespace"
        try {
            RedisTestSupport.aclBackend(user, rules).use { backend ->
                backend.atomicBroadcast.assert().isTrue()
                CacheRuntime.builder().backend(backend).build().use { nodeA ->
                    CacheRuntime.builder().backend(backend).build().use { nodeB ->
                        val a = nodeA.create(spec(name, ""))
                        val b = nodeB.create(spec(name, ""))
                        b["k"].assert().isEqualTo("v-k")
                        a.put("k", "v")
                        awaitCondition("node B sees the write") { b["k"] == "v" }
                        val unlisted = cacheSpec<String, String>("unlisted-$namespace") {
                            namespace = "other-${this@RedisLayoutTest.namespace}"
                            keyPrefix = ""
                            codec = StringCodec
                            externallySourced()
                        }
                        runCatching { nodeA.create(unlisted) }.exceptionOrNull()
                            .assert().isInstanceOf(IllegalStateException::class.java)
                            .hasMessageContaining("channel pattern [&inv:other-$namespace]")
                    }
                }
            }
        } finally {
            RedisTestSupport.deleteAclUser(user)
            RedisTestSupport.withCommands { it.del("$namespace:k") }
        }
    }

    private fun spec(name: String, keyPrefix: String) = cacheSpec<String, String>(name) {
        namespace = this@RedisLayoutTest.namespace
        this.keyPrefix = keyPrefix
        codec = StringCodec
        loader = CacheLoader { "v-$it" }
    }

    /**
     * [mode] 的后端，以唯一命名的 ACL 用户连接（结束时删除）：放行全部 key 与命令，频道只放行 [channels]；原子广播另放行探测频道，
     * 非原子广播不放行它（构造时的探测因此退回非原子广播）。[channels] 为空时以默认用户连接。
     */
    private fun withBackend(mode: Mode, channels: List<String>, block: (RedisBackend) -> Unit) {
        val options = RedisBackendOptions(
            clientName = "cocache-tck-" + UUID.randomUUID(),
            channelMode = if (mode == Mode.SHARDED) RedisChannelMode.SHARDED else RedisChannelMode.PUBSUB
        )
        if (channels.isEmpty() && mode != Mode.NON_ATOMIC) {
            RedisTestSupport.backend(options).use(block)
            return
        }
        val user = "cocache-layout-" + UUID.randomUUID()
        val probe = if (mode == Mode.ATOMIC) listOf("&cocache:!probe:*") else emptyList()
        try {
            RedisTestSupport.aclBackend(
                user,
                listOf("~*", "resetchannels") + channels + probe + "+@all",
                options
            ).use(block)
        } finally {
            RedisTestSupport.deleteAclUser(user)
        }
    }
}
