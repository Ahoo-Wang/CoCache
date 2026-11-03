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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.FutureConditionalL1Store
import me.ahoo.cocache.api.spi.FutureL1Store
import me.ahoo.cocache.api.spi.FutureSetL1Store
import me.ahoo.cocache.api.spi.FutureVersionedL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.RemoteUnavailableException
import me.ahoo.cocache.test.Suspending
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
    private val store: L1Store,
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
            now { store.release(key, token) }
            ledger.giveBack(token)
        }
    }
}

/**
 * 在被包装的内存存储上就地执行挂起操作：它从不挂起（[InMemoryBackend]），结果随调用返回。
 */
internal fun <T> now(block: suspend () -> T): T = Suspending.await(block)

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
 * 确定性测试的挂起式内存后端（RFC 0005 §4.10）：包装 [InMemoryBackend]，存储是 [FutureL1Store]（经 [FutureL1Store.adapt] 交给引擎），
 * 基本操作的每条 L1 命令都挂起排队，由测试决定何时、以什么结局完成
 * （SPI 不保证命令按发出的顺序生效，所以完成顺序任意）。每个运行时经 [view] 拿到自己的视图，命令记下发出的节点：
 * - [crash] 一个节点：它的待完成命令随机生效或不生效，但结果再也送不回去；之后的命令既不生效也不完成；发给它的失效事件被丢弃。
 * - [scriptEvents] 为 `true` 时，失效事件不再同步投递，而是排队，由测试按任意顺序投递、重复或丢失（丢失之后补一个订阅重建事件，
 *   与 Redis 后端以心跳与重新订阅发现丢失一致）。
 *
 * 能力面上的操作（条件读写、按版本写入、集合）不排队，就地转给被包装的存储，以已完成的 future 返回。
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

        override fun store(namespace: String): L1Store = FutureL1Store.adapt(
            ScriptedStore(node, delegate.store(namespace))
        )
    }

    @Suppress("TooManyFunctions")
    private inner class ScriptedStore(
        private val node: Int,
        private val store: L1Store
    ) : FutureL1Store {
        private val conditional = store.conditional()?.let { PassingConditional(it) }
        private val versioned = store.versioned()?.let { PassingVersioned(it) }
        private val sets = store.sets()?.let { PassingSets(it) }

        override fun conditional(): FutureConditionalL1Store? = conditional

        override fun versioned(): FutureVersionedL1Store? = versioned

        override fun sets(): FutureSetL1Store? = sets

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

        private fun grantOf(key: String, read: L1Read, lease: LeaseRequest?, refresh: RefreshRequest?) {
            if (read is L1Read.Miss && read.leaseGranted && lease != null) {
                ledger.grant(lease.token, "lease on $key")
            }
            if (read is L1Read.Hit && read.refreshGranted && refresh != null) {
                ledger.grant(refresh.token, "refresh token on $key")
            }
        }

        override fun read(
            key: String,
            lease: LeaseRequest?,
            refresh: RefreshRequest?,
            timeout: Duration?
        ): CompletableFuture<L1Read> = command(
            "read $key",
            holds = { listOfNotNull(lease?.let { key to it.token }, refresh?.let { key to it.token }) }
        ) {
            now { store.read(key, lease, refresh, timeout) }.also { grantOf(key, it, lease, refresh) }
        }

        override fun readAll(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>,
            timeout: Duration?
        ): CompletableFuture<Map<String, L1Read>> = command(
            "readAll $keys",
            holds = { leases.map { (key, lease) -> key to lease.token } }
        ) {
            now { store.readAll(keys, leases, timeout) }.onEach { (key, read) -> grantOf(key, read, leases[key], null) }
        }

        override fun reclaim(
            key: String,
            observed: ContentFingerprint,
            lease: LeaseRequest
        ): CompletableFuture<Boolean> = command("reclaim $key", holds = { listOf(key to lease.token) }) {
            val reclaimed = now { store.reclaim(key, observed, lease) }
            if (reclaimed) {
                ledger.grant(lease.token, "reclaimed lease on $key")
            }
            reclaimed
        }

        override fun fill(fill: Fill): CompletableFuture<L1Fill> = command(
            "fill ${fill.key}",
            gives = listOf(fill.token),
            holds = { listOf(fill.key to fill.token) }
        ) {
            now { store.fill(fill) }.also { ledger.giveBack(fill.token) }
        }

        override fun fillAll(fills: List<Fill>): CompletableFuture<List<L1Fill>> = command(
            "fillAll ${fills.map { it.key }}",
            gives = fills.map { it.token },
            holds = { fills.map { it.key to it.token } }
        ) {
            now { store.fillAll(fills) }.also { fills.forEach { ledger.giveBack(it.token) } }
        }

        override fun release(key: String, token: Token): CompletableFuture<Void?> = command(
            "release $key",
            gives = listOf(token)
        ) {
            now { store.release(key, token) }
            ledger.giveBack(token)
            null
        }

        override fun put(
            key: String,
            envelope: Envelope,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> = command("put $key") {
            now { store.put(key, envelope, ttl, frame) }
            null
        }

        override fun putAll(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = command("putAll") {
            now { store.putAll(puts, frame) }
            null
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
            command("invalidate $keys") {
                now { store.invalidate(keys, frame) }
                null
            }
    }

    private class PassingConditional(private val surface: ConditionalL1Store) : FutureConditionalL1Store {
        override fun readIfChanged(
            key: String,
            known: Digest?,
            timeout: Duration?
        ): CompletableFuture<L1ConditionalRead> =
            passed { surface.readIfChanged(key, known, timeout) }

        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<L1ConditionalWrite> = passed {
            surface.putIfMatch(
                key,
                expected,
                envelope,
                ttl,
                frame
            )
        }
    }

    private class PassingVersioned(private val surface: VersionedL1Store) : FutureVersionedL1Store {
        override fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<L1VersionedWrite> = passed { surface.putIfNewer(key, envelope, version, ttl, frame) }
    }

    private class PassingSets(private val surface: SetL1Store) : FutureSetL1Store {
        override fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> =
            passed { surface.addMembers(key, members, ttl, frame) }

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Int?> =
            passed { surface.removeMembers(key, members, ttl, frame) }
    }

    private companion object {
        /**
         * 能力面上的操作不排队：就地执行，以已完成（或已失败）的 future 返回。
         */
        @Suppress("TooGenericExceptionCaught")
        fun <T> passed(block: suspend () -> T): CompletableFuture<T> = try {
            CompletableFuture.completedFuture(now(block))
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }
}
