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
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.internal.EncodedKeys
import me.ahoo.cocache.core.internal.LosslessKeys
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.uncancellable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缓存之外的写入方（CDC 消费者、批处理、运维工具）使用的失效入口：不需要创建缓存，
 * 按与缓存相同的协议删除 L1 中的 key 并广播失效帧，各节点随后淘汰 L2。
 *
 * key 与频道按 8.x 的布局构造（RFC 0005 §4.7）：存储 key 为 `{keyPrefix}{namespace}:{encodedKey}`，帧发往该 keyspace 的频道
 * （Redis 为 `{keyPrefix}inv:{namespace}`）。[keyPrefix] 必须与目标缓存相同：前缀不同的缓存不订阅这个频道，也没有这些 key。
 *
 * 与缓存自己的 `invalidate` 一样，原子广播的后端在同一个脚本内完成删除与广播；否则先删除再经通道广播。
 * 调用在调用方线程上等待，规则同缓存的同步 API：L1 调用不可取消，线程被中断时等在途的命令结束之后抛出 [InterruptedException]。
 * 写入方必须先提交数据源，再调用本方法。调用是幂等的：抛出异常时（例如删除成功而广播失败，或 Cluster
 * 中部分 slot 失败）请重试，否则其它节点的 L2 最长陈旧到 `localMaxAge`。
 *
 * [invalidate] 删除 L1 中的 key：用在权威类型（`Store`、`VersionedStore`、`SetStore`：L1 是唯一副本，RFC 0005 §4.1）的命名空间上，删除的就是唯一的一份数据，
 * 不能恢复。只想让各节点丢弃 L2 副本、保留 L1 时用 [broadcast]：它只广播失效帧，对任何类型的缓存都安全（RFC 0005 §4.9）。
 *
 * @param backend 与缓存共用的后端（同一个 Redis 与通道模式）
 * @param keyPrefix 目标缓存的 key 前缀，默认 [CacheSpec.DEFAULT_KEY_PREFIX]
 * @param nodeId 广播帧的来源节点；默认随机，因此所有缓存节点（包括同进程的运行时）都会处理该帧
 */
class ExternalInvalidator @JvmOverloads constructor(
    private val backend: CoherenceBackend,
    private val keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX,
    private val nodeId: NodeId = NodeId.random(),
) : AutoCloseable {
    private val stores = ConcurrentHashMap<Keyspace, L1Store>()
    private val closed = AtomicBoolean(false)

    /**
     * 失效一个命名空间中的若干 key。
     *
     * @param namespace 缓存的命名空间（默认等于缓存名），字符集为 `[A-Za-z0-9._-]+`
     * @param encodedKeys 经 `KeyEncoder` 编码后的 key（存储 key 中 `{keyPrefix}{namespace}:` 之后的部分），不得为空串，
     *   不得以 `{keyPrefix}{namespace}:` 开头（那是完整的 Redis key，缓存的运维接口同样拒绝它），
     *   不得含未配对的 UTF-16 代理（存储 key 与失效帧按 UTF-8 编码，编不出它；缓存同样拒绝这样的 key）
     * @throws IllegalArgumentException 命名空间不符合字符集（[Keyspace]），或有空串、以存储前缀开头、含未配对 UTF-16 代理的 key（什么也不做）
     */
    fun invalidate(namespace: String, encodedKeys: Collection<String>) {
        check(!closed.get()) { "ExternalInvalidator is closed." }
        val keyspace = Keyspace(keyPrefix, namespace)
        if (encodedKeys.isEmpty()) {
            return
        }
        val storageKeys = storageKeysOf(keyspace, encodedKeys)
        val frame = InvalidationFrame.encode(nodeId, storageKeys)
        val store = storeOf(keyspace)
        runSync {
            uncancellable {
                store.invalidate(storageKeys, frame)
                if (!backend.atomicBroadcast) {
                    backend.channel.publish(keyspace, frame)
                }
            }
        }
    }

    /**
     * 每个 keyspace 一个存储句柄。创建句柄时后端可能探测频道（阻塞的网络调用），不放在 `computeIfAbsent` 中：并发的首次调用
     * 至多各创建一次，只保留先登记的那个。与 [close] 并发时：在关闭清空句柄之后才登记的句柄由本次调用丢弃，并拒绝本次调用。
     */
    private fun storeOf(keyspace: Keyspace): L1Store {
        val store = stores[keyspace] ?: backend.store(keyspace).let { stores.putIfAbsent(keyspace, it) ?: it }
        if (closed.get() && stores.remove(keyspace, store)) {
            throw IllegalStateException("ExternalInvalidator is closed.")
        }
        return store
    }

    /**
     * 失效一个 key。
     */
    fun invalidate(namespace: String, encodedKey: String) {
        invalidate(namespace, listOf(encodedKey))
    }

    /**
     * 只广播失效帧，不修改 L1：各节点递增这些 key 的失效戳并淘汰 L2 副本，之后从 L1 重新填充。对权威类型同样安全
     * （不删除唯一副本）。用于 L1 已经是正确内容、只有其它节点的 L2 可能陈旧的场合，例如外部工具已经直接改写了 L1。
     *
     * 投递至多一次，与缓存自己的广播相同：帧丢失时各节点的 L2 陈旧到 `localMaxAge` 或下一次订阅重置。
     *
     * @param namespace 缓存的命名空间，字符集为 `[A-Za-z0-9._-]+`
     * @param encodedKeys 编码后的 key（同 [invalidate]），不得为空串，不得以存储前缀开头，不得含未配对的 UTF-16 代理
     * @throws IllegalArgumentException 命名空间不符合字符集，或有空串、以存储前缀开头、含未配对 UTF-16 代理的 key（什么也不做）
     */
    fun broadcast(namespace: String, encodedKeys: Collection<String>) {
        check(!closed.get()) { "ExternalInvalidator is closed." }
        val keyspace = Keyspace(keyPrefix, namespace)
        if (encodedKeys.isEmpty()) {
            return
        }
        val frame = InvalidationFrame.encode(nodeId, storageKeysOf(keyspace, encodedKeys))
        runSync { uncancellable { backend.channel.publish(keyspace, frame) } }
    }

    /**
     * 去重后的存储 key：先检查全部 key（空串、以存储前缀开头的完整 Redis key [EncodedKeys]、未配对的 UTF-16 代理 [LosslessKeys]），
     * 有一个不合格就在触碰 L1 与通道之前拒绝。
     */
    private fun storageKeysOf(keyspace: Keyspace, encodedKeys: Collection<String>): List<String> {
        require(encodedKeys.none { it.isEmpty() }) { "Encoded keys must not be empty." }
        val namespace = keyspace.namespace
        return encodedKeys.distinct().map {
            EncodedKeys.requireNotStorageKey(NAMESPACE, namespace, keyspace.storagePrefix, it)
            keyspace.storageKey(LosslessKeys.require(NAMESPACE, namespace, it))
        }
    }

    /**
     * 只广播一个 key 的失效帧，见 [broadcast]。
     */
    fun broadcast(namespace: String, encodedKey: String) {
        broadcast(namespace, listOf(encodedKey))
    }

    /**
     * 丢弃本对象创建的存储句柄（之后的调用被拒绝）；不关闭 [backend]（存储没有 `close`，资源属于后端）。
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            stores.clear()
        }
    }

    private companion object {
        /**
         * 拒绝不能无损编码的 key 时信息开头的主体（[LosslessKeys.require]）：这里只知道命名空间，不知道缓存名。
         */
        const val NAMESPACE = "Namespace"
    }
}
