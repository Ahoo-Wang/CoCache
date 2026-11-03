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
     * 能否在写入的同一次原子操作中广播失效。`false` 时，引擎在写入成功之后调用 [InvalidationChannel.publish]。
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
     * 命名空间的存储：所有 key 都已包含命名空间前缀。Java 实现者返回 [FutureL1Store.adapt] 的结果。
     *
     * 可选能力（条件读写、按版本有序的写入、集合）由存储的访问器表达（[L1Store.conditional]、[L1Store.versioned]、[L1Store.sets]）：
     * `runtime.create` 检查缓存需要的能力，缺失时创建失败。
     */
    fun store(namespace: String): L1Store

    override fun close() = Unit
}
