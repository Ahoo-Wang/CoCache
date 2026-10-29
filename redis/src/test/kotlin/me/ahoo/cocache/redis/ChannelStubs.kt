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

import io.lettuce.core.RedisFuture
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * 失效通道单元测试共用的桩：按方法名应答的 Lettuce 接口代理、已完成的 RedisFuture、记录回调的订阅者。
 */

internal fun connectionsOf(pubSub: StatefulRedisPubSubConnection<ByteArray, ByteArray>): RedisConnections {
    val connections = mockk<RedisConnections>()
    every { connections.pubSub } returns pubSub
    every { connections.await(any<CompletableFuture<Any?>>()) } answers { firstArg<CompletableFuture<Any?>>().get() }
    return connections
}

/**
 * 按方法名应答的代理，未列出的方法抛出 [UnsupportedOperationException]。
 */
internal inline fun <reified T> stub(vararg answers: Pair<String, (Array<out Any?>) -> Any?>): T {
    val byName = answers.toMap()
    return proxy { method, args ->
        val answer = byName[method.name] ?: throw UnsupportedOperationException(method.name)
        answer(args)
    }
}

internal class RecordingListener : InvalidationListener {
    val resets = AtomicInteger()
    val received = CopyOnWriteArrayList<List<String>>()

    override fun onInvalidated(keys: List<String>, origin: NodeId) {
        received.add(keys)
    }

    override fun onReset() {
        resets.incrementAndGet()
    }
}

internal inline fun <reified T> proxy(crossinline handler: (java.lang.reflect.Method, Array<out Any?>) -> Any?): T {
    return java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, args ->
        handler(method, args ?: emptyArray())
    } as T
}

internal fun <T> completed(value: T): RedisFuture<T> = FutureAdapter(CompletableFuture.completedFuture(value))

internal fun <T> failed(error: Throwable): RedisFuture<T> = FutureAdapter(CompletableFuture.failedFuture(error))

internal class FutureAdapter<T>(private val future: CompletableFuture<T>) :
    RedisFuture<T>,
    java.util.concurrent.CompletionStage<T> by future,
    java.util.concurrent.Future<T> by future {
    override fun getError(): String? = null

    override fun await(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true

    override fun toCompletableFuture(): CompletableFuture<T> = future
}
