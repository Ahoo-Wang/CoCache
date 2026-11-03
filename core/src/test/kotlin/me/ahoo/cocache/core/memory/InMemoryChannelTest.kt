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

package me.ahoo.cocache.core.memory

import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class InMemoryChannelTest {
    /**
     * 排在前面的订阅者抛出 `Error`：其后的订阅者照常收到失效与重置，写入方也看不到订阅者的异常。
     * `VirtualMachineError` 照常抛出。
     */
    @Test
    fun listenerThrowingAnErrorDoesNotSkipOtherListeners() {
        val backend = InMemoryBackend()
        val failing = FailingListener()
        val healthy = RecordingListener()
        backend.channel.subscribe(Keyspace("cocache:", "ns"), failing)
        backend.channel.subscribe(Keyspace("cocache:", "ns"), healthy)
        failing.error = AssertionError("listener bug")

        Suspending.await { backend.store(Keyspace("cocache:", "ns")).invalidate(listOf("k"), frameOf("k")) }
        Suspending.await { backend.channel.publish(Keyspace("cocache:", "ns"), frameOf("k2")) }
        healthy.received.assert().containsExactly(listOf("k"), listOf("k2"))
        backend.reconnect()
        healthy.resets.get().assert().isEqualTo(2)

        val fatal = OutOfMemoryError("listener")
        failing.error = fatal
        runCatching {
            Suspending.await {
                backend.channel.publish(
                    Keyspace("cocache:", "ns"),
                    frameOf("k3")
                )
            }
        }.exceptionOrNull().assert().isSameAs(fatal)
    }

    private fun frameOf(key: String): ByteArray = InvalidationFrame.encode(NodeId.random(), listOf(key))

    /**
     * 设置 [error] 之后每次回调都抛出它（订阅时的重置不抛出）。
     */
    private class FailingListener : InvalidationListener {
        @Volatile
        var error: Throwable? = null

        override fun onInvalidated(keys: List<String>, origin: NodeId) {
            error?.let { throw it }
        }

        override fun onReset() {
            error?.let { throw it }
        }
    }

    private class RecordingListener : InvalidationListener {
        val resets = AtomicInteger()
        val received = CopyOnWriteArrayList<List<String>>()

        override fun onInvalidated(keys: List<String>, origin: NodeId) {
            received.add(keys)
        }

        override fun onReset() {
            resets.incrementAndGet()
        }
    }
}
