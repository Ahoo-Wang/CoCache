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

package me.ahoo.cocache.api.spi

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * 以 [CompletableFuture] 表达发布的失效通道，供 Java 实现者使用（RFC 0005 §4.3）：方法与 [InvalidationChannel] 同名、语义相同，
 * 只是 [publish] 返回 future。[CoherenceBackend.channel] 返回 [adapt] 的结果。
 *
 * 实现约定同 [FutureL1Store]：
 * - future 可以在 I/O 线程上完成；失败时同步抛出或返回异常完成的 future 都可以，调用方都得到原始异常。
 * - 每次发布都必须自己限时，不得返回永不完成的 future。调用方从不取消返回的 future。
 * - 实现自己取消的 future 与同步抛出的 [CancellationException] 按故障（结果未知）处理：调用方得到以原取消异常为 cause 的
 *   [IllegalStateException]，不是调用方的取消。
 * - [publish] 不得返回 `null`（future 以 `null` 完成是正常的）。
 *
 * 它是接口而不是 [InvalidationChannel] 的基类，理由同 [FutureL1Store]。
 */
interface FutureInvalidationChannel {
    /**
     * 同 [InvalidationChannel.subscribe]：阻塞地订阅，得到服务端确认并回调 [InvalidationListener.onReset] 之后返回。
     */
    fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription

    /**
     * 同 [InvalidationChannel.publish]：发布失效帧，发布完成时以 `null` 完成。
     */
    fun publish(keyspace: Keyspace, frame: ByteArray): CompletableFuture<Void?>

    companion object {
        /**
         * 把 [channel] 适配为 [InvalidationChannel]，等待的规则同 [FutureL1Store.adapt]：
         * - 已完成的 future 直接取结果，不挂起；未完成时挂起，经 `whenComplete` 恢复；只用标准库，不响应协程取消，从不取消 future。
         * - 失败时抛出原始异常（解包 `CompletionException` 与 `ExecutionException`），不会在 kotlinx 的调试模式下被复制。
         * - 被取消的 future 与方法同步抛出的 [CancellationException] 换成以原取消异常为 cause 的 [IllegalStateException]。
         * - 方法同步抛出的其它异常原样传播；返回 `null` 时以指明操作的 [IllegalStateException] 失败。
         * - [subscribe] 原样转发。
         */
        @JvmStatic
        fun adapt(channel: FutureInvalidationChannel): InvalidationChannel = FutureInvalidationChannelAdapter(channel)
    }
}

/**
 * 同 [FutureInvalidationChannel.adapt]。
 */
fun FutureInvalidationChannel.asInvalidationChannel(): InvalidationChannel = FutureInvalidationChannel.adapt(this)

/**
 * [FutureInvalidationChannel.adapt] 的实现。
 */
private class FutureInvalidationChannelAdapter(private val channel: FutureInvalidationChannel) : InvalidationChannel {
    override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription =
        channel.subscribe(keyspace, listener)

    override suspend fun publish(keyspace: Keyspace, frame: ByteArray) {
        awaitNullable(channel, "publish") { channel.publish(keyspace, frame) }
    }

    override fun toString(): String = "FutureInvalidationChannel.adapt($channel)"
}
