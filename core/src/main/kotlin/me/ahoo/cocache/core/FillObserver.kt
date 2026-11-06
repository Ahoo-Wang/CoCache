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

import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.engine.CoherentEngine
import me.ahoo.cocache.core.proxy.CacheProxies

/**
 * 写事务内回源的钩子（RFC 0005 §4.9，C2、S7、S15）：只在未命中路径上调用，默认 [NONE]。通过 [CacheRuntime.Builder.fillObserver]
 * 设置，作用于本运行时创建的所有缓存；只有带数据源的读取经过它（权威副本没有数据源）。
 *
 * - 同步变体（`get`、`lookup`、`getAll`、`lookupAll`、`get(key, loader)`）在 L2 未命中时、调用入口的调用方线程上调用 [enter] 一次，
 *   之后整次调用（L1 往返、等待 lease、回源与写回）只用它返回的 [FillScope]，不在填充时再读线程绑定的状态（S7）。L2 命中不调用。
 * - 异步与挂起变体、提前刷新不调用它：它们的 loader 不在调用方线程上，不在调用方的事务内，一律不门控、不记录。
 * - [enter] 返回非 `null` 的调用作为 SingleFlight 的 leader 时，结果不交给跟随者，跟随者按合并规则自行回源（S15）：
 *   否则其它调用方经 flight 拿到未提交的值，事务回滚之后撤不回。
 */
fun interface FillObserver {
    /**
     * 当前线程所在的、需要门控与记录的事务；不在其中时返回 `null`（这次调用不门控、不记录）。
     * 每次同步未命中调用一次，应当便宜；不应抛出。
     */
    fun enter(): FillScope?

    /**
     * 缓存 [cacheName] 的一次同步未命中：引擎调用的是它，默认同 [enter]。按缓存配置的实现覆盖它，对两个机制都关闭的缓存返回 `null`：
     * 这个缓存的调用因此与 [NONE] 相同，不门控、不记录，事务中的 leader 也照常与跟随者共享结果。
     */
    fun enter(cacheName: String): FillScope? = enter()

    companion object {
        /**
         * 不观察：任何调用都不在事务中。
         */
        @JvmField
        val NONE: FillObserver = object : FillObserver {
            override fun enter(): FillScope? = null

            override fun toString(): String = "FillObserver.NONE"
        }
    }
}

/**
 * 一次同步调用所在的事务（[FillObserver.enter] 的结果）。只在这次调用的调用方线程上被调用。
 */
interface FillScope {
    /**
     * 机制 2（私有回源）：[key] 的回源是否私有。私有回源不申请 lease、不读也不写 L1、不写 L2、不经 SingleFlight 共享，
     * 数据源的结果只交给这次调用，计入 `fillsGated`。
     */
    fun isPrivate(key: FillKey): Boolean

    /**
     * 机制 1（回滚时失效事务内的填充）：数据源为 [key] 产生的值即将进入共享层（L1 或 L2；flight 的结果不交给跟随者）。
     * 在写入之前调用，写入失败时多一次失效，无害。实现在事务回滚、提交结果未知或回滚到 savepoint 时失效它（[FillKey.invalidateAll]）。
     */
    fun filled(key: FillKey)
}

/**
 * 一个缓存中的一个 key（RFC 0005 §4.9，S24）：按 [keyspace]（前缀与命名空间）与 [encodedKey] 相等，与经哪个对象访问无关：
 * 同一 namespace 可以经不同的对象访问（Spring 适配器持有的缓存、接口代理、`JoinCache` 的一侧），按对象身份匹配会漏判。
 */
class FillKey internal constructor(internal val engine: CoherentEngine<*, *>, val encodedKey: String) {
    /**
     * 缓存名。
     */
    val cacheName: String
        get() = engine.name

    /**
     * 缓存的 keyspace（前缀与命名空间）。
     */
    val keyspace: Keyspace
        get() = engine.keyspace

    /**
     * 只在本节点递增失效戳并淘汰 L2 副本，不碰 L1（登记失效时，RFC 0005 §4.9 机制 2）：本节点在途的填充不再写入 L2，
     * 本事务接下来的读取不命中登记之前的本地副本。其它节点不受影响，失效仍在事务结束之后执行。
     */
    fun evictLocal() {
        engine.evictLocal(encodedKey)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is FillKey && encodedKey == other.encodedKey && keyspace == other.keyspace)

    override fun hashCode(): Int = 31 * keyspace.hashCode() + encodedKey.hashCode()

    override fun toString(): String = "FillKey(cache='$cacheName', encodedKey='$encodedKey')"

    companion object {
        /**
         * [cache]（运行时创建的缓存，或它的接口代理）中 [key] 对应的 [FillKey]；不是 CoCache 的单值引擎（如自定义实现、`SetStore`）
         * 时返回 `null`。
         */
        @JvmStatic
        fun of(cache: ReadableCache<*, *>, key: Any): FillKey? {
            val engine = (CacheProxies.targetOf(cache) ?: cache) as? CoherentEngine<*, *> ?: return null
            return engine.fillKeyOf(key)
        }

        /**
         * 走正常的失效路径失效 [keys]（同 [CacheAdmin.invalidateEncoded]：派生副本两次递增戳、淘汰 L2、删除 L1 并广播，
         * 带版本的派生副本保留版本），每个缓存一次。失败按缓存的 `invalidateFailure` 处理（派生副本默认进入重试队列）；
         * 一个缓存抛出时其余缓存照常执行，最后抛出第一个异常，其余作为它的 suppressed。
         *
         * 接受业务 key 编码出的任何 key，包括空串（`""` 是合法的业务 key）：不经运维入口 [CacheAdmin.invalidateEncoded] 的输入校验。
         */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun invalidateAll(keys: Collection<FillKey>) {
            var failure: Exception? = null
            for ((engine, group) in keys.groupBy { it.engine }) {
                try {
                    engine.invalidateEncodedKeys(group.map { it.encodedKey }.distinct())
                } catch (e: Exception) {
                    val first = failure
                    if (first == null) failure = e else first.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }
    }
}
