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
 * 一致性后端：L1 存储与失效通道是同一个后端的两个面，失效应由串行化写入的一方发出（RFC 0005 §4.3）。
 *
 * 连接等资源属于后端，随 [close] 释放：[L1Store] 没有 `close`。
 */
interface CoherenceBackend : AutoCloseable {
    /**
     * 能否在写入的同一次原子操作中广播失效。`false` 时，引擎在写入成功或结果未知之后调用 [InvalidationChannel.publish]。
     * 只广播的失效在任何后端上都经 [InvalidationChannel.publish] 发布。
     */
    val atomicBroadcast: Boolean

    /**
     * 当前实现的线格式版本。
     */
    val format: Int

    /**
     * 失效通道。Java 实现者返回 [FutureInvalidationChannel.adapt] 的结果。
     */
    val channel: InvalidationChannel

    /**
     * [keyspace] 的存储：传给它的 key 都是完整的存储 key（[Keyspace.storagePrefix] 加上编码后的 key）。Java 实现者返回
     * [FutureL1Store.adapt] 的结果。`runtime.create` 为每个缓存调用一次，阻塞。
     *
     * 可选能力（条件读写、按版本有序的写入、集合）由存储的访问器表达（[L1Store.conditional]、[L1Store.versioned]、[L1Store.sets]）：
     * `runtime.create` 检查缓存需要的能力，缺失时创建失败。
     *
     * 有访问控制的后端在这里探测 [keyspace] 的实际频道（RFC 0005 §4.7，K8）：向它发布一个零 key 的帧（监听者什么也不做），
     * 原子广播时与写入脚本一样以该前缀下的 key 执行。无权发布时创建失败，不降级为非原子广播：否则每个写入都在广播处失败，
     * 而之前对 L1 的修改已经生效。
     */
    fun store(keyspace: Keyspace): L1Store

    override fun close() = Unit
}
