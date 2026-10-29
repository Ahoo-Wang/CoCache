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
 * 一致性后端：L1 存储与失效通道是同一个后端的两个面，失效应由串行化写入的一方发出。
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
     * 是否支持按版本有序的写入（6.8）：存储原子地实现 [RemoteStore.putIfNewer]（原生异步的存储同时实现
     * [AsyncRemoteStore.putIfNewerAsync]），fill / put / putAll 时保存 [Envelope.Scalar.version]、读取时原样返回。
     *
     * 默认 `false`：声明为带版本的缓存在创建时即失败，而不是在第一次写入时。
     */
    val versionedWrites: Boolean
        get() = false

    val channel: InvalidationChannel

    /**
     * 命名空间的存储：所有 key 都已包含命名空间前缀。
     */
    fun store(namespace: String): RemoteStore

    override fun close() = Unit
}
