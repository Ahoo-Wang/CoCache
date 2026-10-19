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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存之外的写入方（CDC 消费者、批处理、运维工具）使用的失效入口：不需要创建缓存，
 * 按与缓存相同的协议删除 L1 中的 key 并广播失效帧，各节点随后淘汰 L2。
 *
 * 与缓存自己的 `invalidate` 一样，原子广播的后端在同一个脚本内完成删除与广播；否则先删除再经通道广播。
 * 写入方必须先提交数据源，再调用本方法。
 *
 * @param backend 与缓存共用的后端（同一个 Redis 与通道模式）
 * @param nodeId 广播帧的来源节点；默认随机，因此所有缓存节点（包括同进程的运行时）都会处理该帧
 */
class ExternalInvalidator @JvmOverloads constructor(
    private val backend: CoherenceBackend,
    private val nodeId: NodeId = NodeId.random(),
) : AutoCloseable {
    private val stores = ConcurrentHashMap<String, RemoteStore>()

    /**
     * 失效一个命名空间中的若干 key。
     *
     * @param namespace 缓存的命名空间（默认等于缓存名）
     * @param encodedKeys 经 `KeyEncoder` 编码后的 key（存储 key 中命名空间之后的部分）
     * @param keyPrefix 缓存的 key 前缀，默认 [CacheSpec.DEFAULT_KEY_PREFIX]
     */
    @JvmOverloads
    fun invalidate(
        namespace: String,
        encodedKeys: Collection<String>,
        keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX
    ) {
        if (encodedKeys.isEmpty()) {
            return
        }
        val storageKeys = encodedKeys.distinct().map { "$keyPrefix$namespace:$it" }
        val frame = InvalidationFrame.encode(nodeId, storageKeys)
        stores.computeIfAbsent(namespace) { backend.store(it) }.invalidate(storageKeys, frame)
        if (!backend.atomicBroadcast) {
            backend.channel.publish(namespace, frame)
        }
    }

    /**
     * 失效一个 key。
     */
    @JvmOverloads
    fun invalidate(namespace: String, encodedKey: String, keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX) {
        invalidate(namespace, listOf(encodedKey), keyPrefix)
    }

    /**
     * 关闭本对象创建的存储句柄；不关闭 [backend]。
     */
    override fun close() {
        stores.values.forEach { it.close() }
        stores.clear()
    }
}
