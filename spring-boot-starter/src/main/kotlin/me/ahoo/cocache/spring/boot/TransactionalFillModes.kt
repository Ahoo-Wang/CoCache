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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.spring.tx.FillInTransaction
import java.util.concurrent.ConcurrentHashMap

/**
 * 写事务内回源的两个机制（RFC 0005 §4.9）每个缓存的生效值，`TransactionalFillObserver` 按缓存名读取（有 spring-tx 时）。按字段分层（RFC 0005 §4.8）：
 * 类型默认值（`invalidate-fills-on-rollback: true`、`fill-in-transaction: registered-keys`）→ `cocache.defaults.cache.*` →
 * `cocache.overrides.cache.*`（取更严的值）→ `cocache.caches.[name].*`。没有声明层：spec 与注解不声明这两个字段。
 *
 * 经 `CoCacheFactory` 创建的 `Cache` 与 `VersionedCache` 在分层时登记（[PolicyLayering]）；没有登记的缓存取类型默认值，
 * 其中包括未分层的缓存（直接经 `CacheRuntime.create` 创建，S20）。读取在写事务内的每次同步未命中上发生，只是一次哈希查找。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class TransactionalFillModes {
    private val modes = ConcurrentHashMap<String, Mode>()

    fun modeOf(cacheName: String): Mode = modes[cacheName] ?: Mode.DEFAULT

    fun invalidateFillsOnRollback(cacheName: String): Boolean = modeOf(cacheName).invalidateFillsOnRollback

    fun fillInTransaction(cacheName: String): FillInTransaction = modeOf(cacheName).fillInTransaction

    internal fun register(cacheName: String, mode: Mode) {
        modes[cacheName] = mode
    }

    /**
     * 一个缓存的两个值。
     */
    data class Mode(val invalidateFillsOnRollback: Boolean, val fillInTransaction: FillInTransaction) {
        companion object {
            /**
             * 类型默认值。
             */
            @JvmField
            val DEFAULT = Mode(true, FillInTransaction.REGISTERED_KEYS)
        }
    }
}
