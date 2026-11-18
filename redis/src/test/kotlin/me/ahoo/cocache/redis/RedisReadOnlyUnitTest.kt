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

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisException
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.verify
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 只读的读取路径与推迟的原子广播探测在 Redis 后端上的单元行为：探测失败时的错误信息、探测的次数与重试、只读存储在发出之前的拒绝。
 * 连接单机 Redis（[RedisTestSupport.standaloneUri]）；集群环境下跳过需要单机客户端的用例。
 */
class RedisReadOnlyUnitTest {
    private fun assumeStandalone() = Assumptions.assumeFalse(RedisTestSupport.isCluster)

    /**
     * 原子广播探测在第一个读写存储创建时执行（不在构造时）：探测超时（暂时性错误）不能永久降级为非原子广播，这次创建照常失败，
     * 结果保持“尚未探测”；下一次创建重新探测，成功之后不再探测。只读的读取从不探测。
     */
    @Test
    fun probeTimeoutFailsTheFirstStoreAndIsRetried() {
        assumeStandalone()
        mockkConstructor(RedisConnections::class) {
            every {
                anyConstructed<RedisConnections>().evalAsync<List<Any?>>(RedisScripts.PROBE, any(), any(), false)
            } returns CompletableFuture.failedFuture(RedisCommandTimeoutException("timeout")) andThenAnswer { callOriginal() }
            RedisBackend.standalone(RedisTestSupport.standaloneUri()).use { backend ->
                backend.atomicBroadcastIfProbed.assert().isNull()
                backend.atomicBroadcast.assert().isFalse()
                val keyspace = Keyspace("cocache:", "unit-probe")
                val error = runCatching { backend.store(keyspace) }.exceptionOrNull()
                error.assert().isInstanceOf(RedisException::class.java)
                error?.cause.assert().isInstanceOf(RedisCommandTimeoutException::class.java)
                backend.atomicBroadcastIfProbed.assert().isNull()
                backend.store(keyspace)
                backend.atomicBroadcastIfProbed.assert().isEqualTo(true)
                backend.store(Keyspace("cocache:", "unit-probe-2"))
                verify(exactly = 2) {
                    anyConstructed<RedisConnections>().evalAsync<List<Any?>>(
                        RedisScripts.PROBE,
                        match { keys -> keys.single().contentEquals(RedisBackend.PROBE_KEY_NAME.toByteArray()) },
                        any(),
                        false
                    )
                }
            }
        }
    }

    /**
     * 只读探测失败时，创建的错误指明缺少什么（回复取自真实服务端：Redis 6.2.24 与 7.4.11）；超时等其它错误原样抛出（返回 `null`）。
     */
    @Test
    fun readProbeFailureNamesWhatIsMissing() {
        val keyspace = Keyspace("cocache:", "docs")
        fun failure(reply: String) = RedisBackend.readProbeFailure(keyspace, RedisCommandExecutionException(reply))
        // Redis 6.2：没有只读脚本
        failure(
            "ERR unknown command `EVALSHA_RO`, with args beginning with: `0000`, `1`, `cocache:docs:`, "
        )?.message.assert()
            .contains("Read-only store of Keyspace(keyPrefix='cocache:', namespace='docs')")
            .contains("read-only caches need Redis 7.0 or later, or Valkey 7.2 or later")
            .contains("Redis replied: ERR unknown command `EVALSHA_RO`")
        failure("NOPERM No permissions to access a key")?.message.assert()
            .contains("Redis denied reading the key prefix [cocache:docs:]: allow the key pattern [%R~cocache:docs:*]")
        failure("NOPERM User reader has no permissions to run the 'evalsha_ro' command")?.message.assert()
            .contains("Redis denied the command [evalsha_ro]: allow [+evalsha_ro]")
        failure(
            "ERR ACL failure in script: User reader has no permissions to run the 'hkeys' command script: 1239, on @user_script:1."
        )?.message.assert()
            .contains("Redis denied the command [hkeys]: allow [+hkeys]")
            .contains("a read-only store needs ${RedisBackend.READ_ONLY_COMMANDS}")
        // 不带命令名的拒绝：列出全部需要的命令与 key 模式
        failure("NOPERM this user has no permissions to run one of the commands")?.message.assert()
            .contains("allow ${RedisBackend.READ_ONLY_COMMANDS} and the key pattern [%R~cocache:docs:*]")
        failure("ERR The user executing the script can't run this command or subcommand")?.message.assert()
            .contains("allow ${RedisBackend.READ_ONLY_COMMANDS}")
        RedisBackend.readProbeFailure(keyspace, RedisException("Command timed out after PT2S.")).assert().isNull()
        failure("ERR something else").assert().isNull()
    }

    /**
     * 只读存储从不探测原子广播；读写存储第一次创建时探测一次，同一个后端之后的读写存储不再探测（与只读存储的先后无关）。
     */
    @Test
    fun backendWithBothKindsOfStoreProbesOnce() {
        assumeStandalone()
        mockkConstructor(RedisConnections::class) {
            every { anyConstructed<RedisConnections>().evalAsync<Any?>(any(), any(), any(), any()) } answers { callOriginal() }
            RedisBackend.standalone(RedisTestSupport.standaloneUri()).use { backend ->
                backend.readOnlyStore(Keyspace("cocache:", "unit-ro-a"))
                backend.atomicBroadcastIfProbed.assert().isNull()
                backend.store(Keyspace("cocache:", "unit-rw-a"))
                backend.readOnlyStore(Keyspace("cocache:", "unit-ro-b"))
                backend.store(Keyspace("cocache:", "unit-rw-b"))
                backend.atomicBroadcastIfProbed.assert().isEqualTo(true)
                verify(exactly = 1) {
                    anyConstructed<RedisConnections>().evalAsync<List<Any?>>(
                        RedisScripts.PROBE,
                        match { keys -> keys.single().contentEquals(RedisBackend.PROBE_KEY_NAME.toByteArray()) },
                        any(),
                        any()
                    )
                }
                verify(exactly = 2) {
                    anyConstructed<RedisConnections>().evalAsync<Any?>(RedisScripts.READ_PROBE, any(), any(), true)
                }
            }
        }
    }

    /**
     * 只读存储：带令牌的读取以 [IllegalArgumentException]、写入以 [UnsupportedOperationException] 在发出之前拒绝。
     */
    @Test
    fun readOnlyRedisStoreRefusesTokensAndWritesBeforeSending() {
        assumeStandalone()
        RedisBackend.standalone(RedisTestSupport.standaloneUri()).use { backend ->
            val keyspace = Keyspace("cocache:", "unit-ro-refuse")
            val store = backend.readOnlyStore(keyspace)
            val key = keyspace.storageKey("k")
            val token = Token(NodeId.random(), 1)
            val lease = LeaseRequest(token, Duration.ofSeconds(1))
            Suspending.await {
                runCatching { store.read(key, lease, null, null) }.exceptionOrNull()
                    .assert().isInstanceOf(IllegalArgumentException::class.java)
                runCatching { store.readAll(listOf(key), mapOf(key to lease), null) }.exceptionOrNull()
                    .assert().isInstanceOf(IllegalArgumentException::class.java)
                listOf<suspend () -> Any?>(
                    { store.release(key, token) },
                    { store.fillAll(listOf(Fill(key, token, Envelope.Absent, null))) },
                    { store.reclaim(key, ContentFingerprint.of(Envelope.Absent, 2), lease) },
                    { store.put(key, Envelope.Absent, null, ByteArray(0)) },
                    { store.putAll(emptyList(), ByteArray(0)) },
                    { store.invalidate(listOf(key), ByteArray(0)) },
                    {
                        store.conditional()?.deleteIfMatch(
                            key,
                            Digest.ofPayload(ByteArray(0)),
                            ByteArray(0)
                        )
                    },
                    { store.versioned()?.evictKeepingVersion(key, null, ByteArray(0)) },
                    { store.sets()?.addMembers(key, emptyList(), null, ByteArray(0)) },
                ).forEach { call ->
                    runCatching { call() }.exceptionOrNull().assert().isInstanceOf(
                        UnsupportedOperationException::class.java
                    )
                }
                store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
            }
        }
    }
}
