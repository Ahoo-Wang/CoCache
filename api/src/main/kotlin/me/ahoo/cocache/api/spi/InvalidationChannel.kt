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

/**
 * 失效通道：至多一次投递（RFC 0005 §4.3）。Java 实现者实现 [FutureInvalidationChannel]，经 [FutureInvalidationChannel.adapt] 得到本接口。
 *
 * 实现约定：
 * - 每个 [Keyspace] 一个频道，频道名由实现按前缀与命名空间构造：前缀不同、命名空间相同的两个 keyspace 不得共用频道。
 * - 每次订阅（重新）建立并得到服务端确认之后，必须回调该连接上所有订阅者的 [InvalidationListener.onReset]。
 * - 读不懂的帧（未知版本、未知类型、解码失败，见 [InvalidationFrame.decode]）按该频道的 [InvalidationListener.onReset] 处理，
 *   不得丢弃（RFC 0005 §4.7，D3）：丢弃的就是一次失效，而重置只会多淘汰。
 * - 订阅者收到的是编码 key，不是帧中的存储 key：以 [Keyspace.encodedKeys] 去掉 `{keyPrefix}{namespace}:`（RFC 0005 §4.9，S4）。
 * - 同一个 keyspace 的订阅者按订阅的先后依次回调，前一个返回之后才回调下一个（帧与重置都是）。缓存在 `runtime.create` 时订阅，
 *   因此之后订阅的外部监听者收到失效或重置时，引擎已经淘汰了本地副本（递增戳并淘汰 L2）。
 */
interface InvalidationChannel {
    /**
     * 订阅 [keyspace] 的频道（阻塞）：只在 `runtime.create` 时调用一次，得到服务端确认并回调 [InvalidationListener.onReset] 之后返回。
     */
    fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription

    /**
     * 发布失效帧（挂起，在写路径上）：仅当 [CoherenceBackend.atomicBroadcast] 为 `false` 时由引擎在写入之后调用。
     *
     * 限时、取消与失败的约定同 [L1Store]：引擎从不取消本调用（在 `NonCancellable` 中调用），实现必须自己限时，
     * 故障不得以 `CancellationException` 报告。
     */
    suspend fun publish(keyspace: Keyspace, frame: ByteArray)
}

/**
 * 失效订阅者。
 */
interface InvalidationListener {
    /**
     * 收到失效帧：[keys] 是编码 key，即存储 key 中 `{keyPrefix}{namespace}:` 之后的部分（[Keyspace.encodedKeys]，
     * 与 `KeyEncoder` 的输出、`ExternalInvalidator` 的参数相同），不属于这个 keyspace 的 key 已被去掉。
     * [keys] 为空的帧是存储创建时的频道探测（[CoherenceBackend.store]），什么也不做。
     */
    fun onInvalidated(keys: List<String>, origin: NodeId)

    /**
     * 订阅（重新）建立：此前的事件可能已丢失，必须放弃所有本地副本。
     */
    fun onReset()
}

/**
 * 订阅句柄，关闭即取消订阅。
 *
 * 关闭不等待正在投递的回调：[close] 返回时已在投递中的 [InvalidationListener.onInvalidated] 或
 * [InvalidationListener.onReset] 可能在它返回之后才到达。只有在 [close] 返回之后才到达的消息与重置保证不再投递。
 * 关闭之后不能再收到回调的订阅者需要自己忽略迟到的回调。
 */
fun interface Subscription : AutoCloseable {
    /**
     * 取消订阅。只保证之后到达的消息不再投递；已在投递中的回调可能在返回之后到达。
     */
    override fun close()
}
