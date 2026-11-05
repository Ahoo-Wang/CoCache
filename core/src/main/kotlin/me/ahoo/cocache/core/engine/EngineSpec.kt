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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyFilter
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.UnknownReadMode

/**
 * 缓存的副本类型（RFC 0005 §4.1）：决定写入哪些段、读取是否申请 lease、读取方是否校验摘要。
 */
internal enum class Role {
    /**
     * 派生副本（`Cache`、`VersionedCache`）：写入不带写入标识与摘要段；未命中申请 lease；读到摘要段是类型不符（D4）。
     */
    DERIVED,

    /**
     * 权威标量（`Store`）：每次写入带写入标识与摘要段；读取不申请 lease；读取方校验摘要（K3）。
     */
    STORE,

    /**
     * 权威的带版本副本（`VersionedStore`）：每次写入带写入标识（带版本的写入不带摘要段）；读取不申请 lease。
     */
    VERSIONED_STORE;

    val authoritative: Boolean
        get() = this != DERIVED
}

/**
 * 引擎的声明：由各种 spec 换算而来（[engineSpec]），引擎只依赖它，不依赖公开的 spec 类型。
 *
 * @param source 声明的数据源；`null` 表示没有（外部来源的 `Cache` 与权威类型）
 * @param versioned 引擎的值是否为 `Versioned<V?>`（[VersionedFormat]）
 */
internal class EngineSpec<K : Any, V : Any>(
    val name: String,
    val keyspace: Keyspace,
    val keyEncoder: KeyEncoder<K>,
    val policy: CachePolicy,
    val format: ValueFormat<V>,
    val source: AsyncSource<K, V>?,
    val role: Role,
    val versioned: Boolean,
    val ttlOf: TtlFunction<K, V>?,
    val keyFilter: KeyFilter?,
    val unknownRead: UnknownReadMode,
)

internal fun <K : Any, V : Any> CacheSpec<K, V>.engineSpec(): EngineSpec<K, V> = EngineSpec(
    name = name,
    keyspace = keyspace,
    keyEncoder = keyEncoder,
    policy = policy,
    format = CodecFormat(codec),
    source = sourceOf(loader, asyncLoader),
    role = Role.DERIVED,
    versioned = false,
    ttlOf = ttlOf,
    keyFilter = keyFilter,
    unknownRead = unknownRead,
)

internal fun <K : Any, V : Any> StoreSpec<K, V>.engineSpec(): EngineSpec<K, V> = EngineSpec(
    name = name,
    keyspace = keyspace,
    keyEncoder = keyEncoder,
    policy = policy,
    format = CodecFormat(codec),
    source = null,
    role = Role.STORE,
    versioned = false,
    ttlOf = ttlOf,
    keyFilter = keyFilter,
    unknownRead = unknownRead,
)

/**
 * 带版本的缓存：引擎的值类型为 `Versioned<V?>`（[VersionedFormat]）。数据源只产出值（out 投影），按 `Versioned<V?>` 使用是安全的；
 * `ttlOf` 只作用于有值的版本（带版本的不存在没有值可交给它，按墓碑寿命）。
 */
@Suppress("UNCHECKED_CAST")
internal fun <K : Any, V : Any> VersionedCacheSpec<K, V>.engineSpec(): EngineSpec<K, Versioned<V?>> = EngineSpec(
    name = name,
    keyspace = keyspace,
    keyEncoder = keyEncoder,
    policy = policy,
    format = VersionedFormat(codec),
    source = sourceOf(
        versionedLoader as CacheLoader<K, Versioned<V?>>?,
        versionedAsyncLoader as AsyncCacheLoader<K, Versioned<V?>>?
    ),
    role = Role.DERIVED,
    versioned = true,
    ttlOf = versionedTtlOf(ttlOf),
    keyFilter = keyFilter,
    unknownRead = UnknownReadMode.THROW,
)

internal fun <K : Any, V : Any> VersionedStoreSpec<K, V>.engineSpec(): EngineSpec<K, Versioned<V?>> = EngineSpec(
    name = name,
    keyspace = keyspace,
    keyEncoder = keyEncoder,
    policy = policy,
    format = VersionedFormat(codec),
    source = null,
    role = Role.VERSIONED_STORE,
    versioned = true,
    ttlOf = versionedTtlOf(ttlOf),
    keyFilter = keyFilter,
    unknownRead = unknownRead,
)

private fun <K : Any, V : Any> sourceOf(loader: CacheLoader<K, V>?, asyncLoader: AsyncCacheLoader<K, V>?): AsyncSource<K, V>? =
    asyncLoader?.let { AsyncSource.Native(it) } ?: loader?.let { AsyncSource.Blocking(it) }

private fun <K : Any, V : Any> versionedTtlOf(ttlOf: TtlFunction<K, V>?): TtlFunction<K, Versioned<V?>>? =
    ttlOf?.let { function ->
        TtlFunction { key, value ->
            value.value?.let { present -> function.ttlOf(key, present) }
        }
    }
