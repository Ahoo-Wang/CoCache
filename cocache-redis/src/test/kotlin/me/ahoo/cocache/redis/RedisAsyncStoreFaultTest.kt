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

import io.lettuce.core.RedisException
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * Redis 原生异步存储的故障与边界（RFC 0004 §4.3）：脚本缓存丢失、命令超时、非运行时异常、取消在途命令、在途期间断连。
 */
class RedisAsyncStoreFaultTest {
    private val namespace = "async-fault-" + UUID.randomUUID().toString().substring(0, 8)
    private val node = NodeId.random()
    private var sequence = 0L
    private val closeables = ArrayList<AutoCloseable>()

    @AfterEach
    fun destroy() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables.add(it) }

    private fun key(name: String) = "cocache:$namespace:$name"

    private fun token() = Token(node, ++sequence)

    private fun frame(vararg keys: String) = InvalidationFrame.encode(node, keys.toList())

    private fun scalar(text: String) = Envelope.Scalar(text.toByteArray())

    private fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)

    private fun <T> CompletableFuture<T>.failure(): Throwable? = handle { _, e -> e }.get(10, TimeUnit.SECONDS)

    /**
     * 暂停所有客户端的命令 [millis] 毫秒（服务端行为，暂停期间发出的命令在服务端排队）。仅限单机。
     */
    private fun pauseClients(millis: Long) {
        RedisTestSupport.withCommands { it.clientPause(millis) }
    }

    @Test
    fun everyAsyncOperationSurvivesScriptCacheLoss() {
        val backend = RedisTestSupport.backend().closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        RedisTestSupport.flushScripts()
        store.readAsync(key("a"), lease, null).await().assert().isEqualTo(RemoteRead.Miss(true))
        RedisTestSupport.flushScripts()
        store.fillAsync(Fill(key("a"), lease.token, scalar("v"), Duration.ofMinutes(1))).await().assert().isTrue()
        RedisTestSupport.flushScripts()
        (
            store.readAsync(
                key("a"),
                null,
                RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofSeconds(10))
            ).await() as RemoteRead.Hit
            )
            .envelope.assert().isEqualTo(scalar("v"))
        RedisTestSupport.flushScripts()
        val leases = mapOf(key("b") to LeaseRequest(token(), Duration.ofSeconds(30)))
        store.readAllAsync(
            listOf(key("a"), key("b")),
            leases
        ).await()[key("b")].assert().isEqualTo(RemoteRead.Miss(true))
        RedisTestSupport.flushScripts()
        store.fillAllAsync(listOf(Fill(key("b"), leases.getValue(key("b")).token, scalar("w"), null))).await()
            .assert().containsExactly(true)
        RedisTestSupport.flushScripts()
        store.releaseAsync(key("b"), token()).await()
        RedisTestSupport.flushScripts()
        store.putAsync(key("c"), scalar("p"), null, frame(key("c"))).await()
        RedisTestSupport.flushScripts()
        store.putAllAsync(listOf(Put(key("d"), scalar("q"), null)), frame(key("d"))).await()
        RedisTestSupport.flushScripts()
        store.reclaimAsync(
            key("c"),
            Digest.of(scalar("other")),
            LeaseRequest(token(), Duration.ofSeconds(30))
        ).await().assert().isFalse()
        RedisTestSupport.flushScripts()
        store.addMembersAsync(key("s"), listOf("m".toByteArray()), null, frame(key("s"))).await()
        RedisTestSupport.flushScripts()
        store.removeMembersAsync(key("s"), listOf("m".toByteArray()), null, frame(key("s"))).await()
        RedisTestSupport.flushScripts()
        store.invalidateAsync(
            listOf(key("a"), key("b"), key("c"), key("d")),
            frame(key("a"), key("b"), key("c"), key("d"))
        ).await()
        store.read(key("a"), null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * 命令超过 `commandTimeout`：与同步等待相同的 [RedisException]，底层命令被取消，连接随后照常可用。
     */
    @Test
    fun asyncCommandTimeoutFailsLikeTheSyncPathAndTheConnectionRecovers() {
        assumeFalse(RedisTestSupport.isCluster)
        val backend = RedisTestSupport.backend(RedisBackendOptions(commandTimeout = Duration.ofMillis(200))).closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        pauseClients(1_000)
        val error = store.readAsync(key("slow"), null, null).failure()
        error.assert().isInstanceOf(RedisException::class.java)
        error?.message.assert().contains("timed out")
        // 批量操作：全部完成后以第一个异常失败（与同步版本相同的异常类型）
        val leases = mapOf(key("b1") to LeaseRequest(token(), Duration.ofSeconds(30)))
        listOf(
            store.readAllAsync(listOf(key("b1"), key("b2")), leases),
            store.fillAllAsync(listOf(Fill(key("b1"), token(), scalar("v"), null))),
            store.putAllAsync(listOf(Put(key("b3"), scalar("p"), null)), frame(key("b3"))),
            store.invalidateAsync(listOf(key("b1"), key("b2")), frame(key("b1"), key("b2"))),
        ).forEach { future -> future.failure().assert().isInstanceOf(RedisException::class.java) }
        Thread.sleep(1_000)
        store.readAsync(key("slow"), null, null).await().assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * 取消返回的 future 只取消调用方的等待：命令照常执行完，连接照常可用。
     */
    @Test
    fun cancellingAnInFlightAsyncCommandLeavesTheConnectionUsable() {
        assumeFalse(RedisTestSupport.isCluster)
        val backend = RedisTestSupport.backend().closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        // 先缓存脚本：否则暂停期间的 EVALSHA 得到 NOSCRIPT 后才发出 EVAL，晚于随后的读取
        store.putAsync(key("warm"), scalar("w"), null, frame(key("warm"))).await()
        store.readAsync(key("warm"), null, null).await()
        pauseClients(500)
        val pending = store.putAsync(key("cancel"), scalar("v"), null, frame(key("cancel")))
        pending.cancel(true).assert().isTrue()
        // 命令在服务端排队，暂停结束后照常执行；连接照常可用
        me.ahoo.cocache.test.Concurrency.awaitCondition("the cancelled put still ran") {
            (store.readAsync(key("cancel"), null, null).await() as? RemoteRead.Hit)?.envelope == scalar("v")
        }
    }

    /**
     * 命令在途期间连接断开：future 以异常完成（不会永远挂起），不在断线期间缓冲重放。
     */
    @Test
    fun disconnectWhileAnAsyncCommandIsInFlightFailsTheFuture() {
        assumeFalse(RedisTestSupport.isCluster)
        val proxy = RedisTestSupport.proxy().closing()
        val backend = RedisTestSupport.proxiedBackend(proxy).closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        store.readAsync(key("warm"), null, null).await()
        pauseClients(1_000)
        val pending = store.putAsync(key("cut"), scalar("v"), null, frame(key("cut")))
        val restore = proxy.cut()
        pending.failure().assert().isNotNull()
        restore.close()
    }

    @Test
    fun boundedMapsNonRuntimeFailuresAndUnwrapsCompletionExceptions() {
        assumeFalse(RedisTestSupport.isCluster)
        val client = RedisTestSupport.standaloneClient()
        val connections = RedisConnections(client, Duration.ofSeconds(2))
        try {
            val checked = Exception("checked")
            val mapped = connections.bounded(CompletableFuture.failedFuture<String>(checked)).failure()
            mapped.assert().isInstanceOf(RedisException::class.java)
            mapped?.cause.assert().isSameAs(checked)
            val runtime = IllegalStateException("runtime")
            connections.bounded(
                CompletableFuture.failedFuture<String>(CompletionException(runtime))
            ).failure().assert().isSameAs(runtime)
            connections.bounded(CompletableFuture.completedFuture("v")).await().assert().isEqualTo("v")
            val transformFailure = IllegalArgumentException("transform")
            connections.mapped(
                CompletableFuture.completedFuture("v")
            ) { throw transformFailure }.failure().assert().isSameAs(transformFailure)
            connections.mapped(
                CompletableFuture.failedFuture<String>(CompletionException(runtime))
            ) { it }.failure().assert().isSameAs(runtime)
        } finally {
            connections.close()
            client.shutdown()
        }
    }
}
