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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.RemoteUnavailableException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * 挂起的 L1 命令的结局：正常完成；失败且未生效；生效但调用方看到超时（存储按 SPI 约定自己释放命令取得或交还的令牌）。
 */
internal enum class Outcome { OK, FAIL, TIMEOUT }

/**
 * 令牌账本：只有在授予之后生效的交还才算数（释放先于授予到达 L1 时什么也不做）。崩溃的节点持有的令牌不再追究（由 `lease.ttl` 回收）。
 */
internal class Ledger {
    private val granted = LinkedHashMap<Token, String>()
    private val returned = HashSet<Token>()
    private val excused = HashSet<NodeId>()

    @Synchronized
    fun grant(token: Token, what: String) {
        granted[token] = what
    }

    @Synchronized
    fun giveBack(token: Token) {
        if (granted.containsKey(token)) {
            returned += token
        }
    }

    @Synchronized
    fun excuse(node: NodeId) {
        excused += node
    }

    @Synchronized
    fun unreturned(): Collection<String> = granted.filterKeys { it !in returned && it.node !in excused }.values
}

/**
 * 挂起的 L1 命令：[apply] 在 L1 上执行（记录授予与交还），[gives] 是命令交还的令牌，[holds] 是超时时存储自己释放的令牌。
 */
internal class Command<T>(
    val name: String,
    val node: Int,
    private val ledger: Ledger,
    private val store: RemoteStore,
    private val gives: List<Token>,
    private val holds: () -> List<Pair<String, Token>>,
    private val apply: () -> T,
) {
    val future = CompletableFuture<T>()

    fun resolve(outcome: Outcome) {
        when (outcome) {
            Outcome.OK -> future.complete(apply())
            Outcome.FAIL -> {
                gives.forEach(ledger::giveBack)
                future.completeExceptionally(RemoteUnavailableException(name))
            }

            Outcome.TIMEOUT -> {
                apply()
                releaseHeld()
                future.completeExceptionally(TimeoutException("$name timed out."))
            }
        }
    }

    /**
     * 节点崩溃：命令可能已经到达 L1（[applied]），但结果再也送不回去。
     */
    fun abandon(applied: Boolean) {
        if (applied) {
            apply()
        }
    }

    private fun releaseHeld() {
        holds().forEach { (key, token) ->
            store.release(key, token)
            ledger.giveBack(token)
        }
    }
}

/**
 * 失效通道上待投递的事件：投递给节点 [node] 的 [listener]。[reset] 为 `true` 时是订阅重建（此前的事件可能已丢失）。
 *
 * @param tag 发布时由 [ScriptedBackend.tagger] 给出（例如发布时数据源的版本），供裁判使用
 */
internal class Event(
    val node: Int,
    val listener: InvalidationListener,
    val keys: List<String>,
    val origin: NodeId?,
    val tag: Any?,
    val reset: Boolean = false,
)

/**
 * 确定性测试的挂起式内存后端（RFC 0005 §4.10）：包装 [InMemoryBackend]，每条异步 L1 命令都挂起排队，由测试决定何时、以什么结局完成
 * （SPI 不保证命令按发出的顺序生效，所以完成顺序任意）。每个运行时经 [view] 拿到自己的视图，命令记下发出的节点：
 * - [crash] 一个节点：它的待完成命令随机生效或不生效，但结果再也送不回去；之后的命令既不生效也不完成；发给它的失效事件被丢弃。
 * - [scriptEvents] 为 `true` 时，失效事件不再同步投递，而是排队，由测试按任意顺序投递、重复或丢失（丢失之后补一个订阅重建事件，
 *   与 Redis 后端以心跳与重新订阅发现丢失一致）。
 *
 * 同步 SPI 直接转给被包装的后端：这里只驱动异步变体。
 */
internal class ScriptedBackend(
    private val delegate: InMemoryBackend,
    private val ledger: Ledger,
    private val scriptEvents: Boolean = false,
    private val tagger: (List<String>) -> Any? = { null },
) {
    private val pending = ArrayList<Command<*>>()
    private val events = ArrayList<Event>()
    private val crashed = HashSet<Int>()

    val pendingCount: Int
        @Synchronized get() = pending.size

    val eventCount: Int
        @Synchronized get() = events.size

    fun view(node: Int): CoherenceBackend = NodeView(node)

    @Synchronized
    private fun enqueue(command: Command<*>) {
        pending += command
    }

    @Synchronized
    private fun take(index: (Int) -> Int): Command<*>? =
        if (pending.isEmpty()) null else pending.removeAt(index(pending.size))

    fun resolveRandom(random: Random, outcome: Outcome): Boolean = take { random.nextInt(it) }?.resolve(outcome) != null

    fun resolveOldest(outcome: Outcome): Boolean = take { 0 }?.resolve(outcome) != null

    fun resolveNewest(outcome: Outcome): Boolean = take { it - 1 }?.resolve(outcome) != null

    /**
     * 节点崩溃，见类注释。
     */
    fun crash(node: Int, random: Random) {
        val abandoned = synchronized(this) {
            crashed += node
            events.removeAll { it.node == node }
            pending.filter { it.node == node }.also { pending.removeAll(it.toSet()) }
        }
        abandoned.forEach { it.abandon(applied = random.nextBoolean()) }
    }

    @Synchronized
    private fun isCrashed(node: Int): Boolean = node in crashed

    /**
     * 取出一个待投递的事件（随机），不投递。
     */
    @Synchronized
    fun takeEvent(random: Random): Event? = if (events.isEmpty()) null else events.removeAt(random.nextInt(events.size))

    @Synchronized
    private fun enqueue(event: Event) {
        if (event.node !in crashed) {
            events += event
        }
    }

    /**
     * 投递事件；节点已崩溃时丢弃。返回是否投递了。
     */
    fun deliver(event: Event): Boolean {
        if (isCrashed(event.node)) {
            return false
        }
        if (event.reset) {
            event.listener.onReset()
        } else {
            event.listener.onInvalidated(event.keys, requireNotNull(event.origin))
        }
        return true
    }

    /**
     * 再排一份同样的事件（重复投递）。
     */
    fun duplicate(event: Event) = enqueue(event)

    /**
     * 丢失事件：之后补一个订阅重建事件给同一个节点。
     */
    fun lose(event: Event) = enqueue(Event(event.node, event.listener, emptyList(), null, null, reset = true))

    private inner class NodeView(private val node: Int) : CoherenceBackend by delegate {
        override val channel: InvalidationChannel = object : InvalidationChannel by delegate.channel {
            override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
                val target = if (scriptEvents) Queueing(listener) else listener
                return delegate.channel.subscribe(namespace, target)
            }
        }

        /**
         * 排队的订阅者：失效事件排队等待投递；订阅建立时的重置照常同步投递。
         */
        private inner class Queueing(private val listener: InvalidationListener) : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) =
                enqueue(Event(node, listener, keys, origin, tagger(keys)))

            override fun onReset() = listener.onReset()
        }

        override fun store(namespace: String): RemoteStore =
            ScriptedStore(node, delegate.store(namespace) as AsyncRemoteStore)
    }

    private inner class ScriptedStore(
        private val node: Int,
        private val store: AsyncRemoteStore
    ) : AsyncRemoteStore by store {
        private fun <T> command(
            name: String,
            gives: List<Token> = emptyList(),
            holds: () -> List<Pair<String, Token>> = { emptyList() },
            apply: () -> T
        ): CompletableFuture<T> {
            val command = Command(name, node, ledger, store, gives, holds, apply)
            // 崩溃的节点发出的命令既不生效也不完成
            if (!isCrashed(node)) {
                enqueue(command)
            }
            return command.future
        }

        private fun grantOf(key: String, read: RemoteRead, lease: LeaseRequest?, refresh: RefreshRequest?) {
            if (read is RemoteRead.Miss && read.leaseGranted && lease != null) {
                ledger.grant(lease.token, "lease on $key")
            }
            if (read is RemoteRead.Hit && read.refreshGranted && refresh != null) {
                ledger.grant(refresh.token, "refresh token on $key")
            }
        }

        override fun readAsync(
            key: String,
            lease: LeaseRequest?,
            refresh: RefreshRequest?
        ): CompletableFuture<RemoteRead> = command(
            "read $key",
            holds = { listOfNotNull(lease?.let { key to it.token }, refresh?.let { key to it.token }) }
        ) {
            store.read(key, lease, refresh).also { grantOf(key, it, lease, refresh) }
        }

        override fun readAllAsync(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>
        ): CompletableFuture<Map<String, RemoteRead>> = command(
            "readAll $keys",
            holds = { leases.map { (key, lease) -> key to lease.token } }
        ) {
            store.readAll(keys, leases).onEach { (key, read) -> grantOf(key, read, leases[key], null) }
        }

        override fun reclaimAsync(
            key: String,
            observed: Digest,
            lease: LeaseRequest
        ): CompletableFuture<Boolean> = command("reclaim $key", holds = { listOf(key to lease.token) }) {
            val reclaimed = store.reclaim(key, observed, lease)
            if (reclaimed) {
                ledger.grant(lease.token, "reclaimed lease on $key")
            }
            reclaimed
        }

        override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = command(
            "fill ${fill.key}",
            gives = listOf(fill.token),
            holds = { listOf(fill.key to fill.token) }
        ) {
            store.fill(fill).also { ledger.giveBack(fill.token) }
        }

        override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> = command(
            "fillAll ${fills.map { it.key }}",
            gives = fills.map { it.token },
            holds = { fills.map { it.key to it.token } }
        ) {
            store.fillAll(fills).also { fills.forEach { ledger.giveBack(it.token) } }
        }

        override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> = command(
            "release $key",
            gives = listOf(token)
        ) {
            store.release(key, token)
            ledger.giveBack(token)
            null
        }

        override fun putAsync(
            key: String,
            envelope: Envelope,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> = command("put $key") {
            store.put(key, envelope, ttl, frame)
            null
        }

        override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = command("putAll") {
            store.putAll(puts, frame)
            null
        }

        override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
            command("invalidate $keys") {
                store.invalidate(keys, frame)
                null
            }
    }
}
