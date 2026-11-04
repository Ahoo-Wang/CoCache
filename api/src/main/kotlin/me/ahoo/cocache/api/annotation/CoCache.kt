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

package me.ahoo.cocache.api.annotation

/**
 * 接口式声明缓存（RFC 0003 §4.12）：`@CoCache interface UserCache : Cache<String, User>`，
 * 由 `@EnableCoCache(caches = [UserCache::class])` 注册为 bean。
 *
 * 接口继承的用户 API 类型决定缓存的类型（RFC 0005 §3.1）：`Cache`、`VersionedCache`、`Store`、`VersionedStore` 或 `SetStore`，
 * 恰好一个。注解只是声明的另一种写法：启动时翻译为对应的 spec，与 spec bean 走同一个引擎；
 * `CacheSpecCustomizer` 与配置文件 `cocache.caches.[name].*` 照常生效（优先级高于注解）。
 *
 * 时长单位均为秒；[UNSET] 表示未设置（使用默认值或配置文件），[FOREVER] 表示永不过期，只能用于 [ttl]（以及 `@CaffeineCache.expireAfterAccess`，表示关闭），用于其它属性时启动失败；[refreshWindow] 为 `0` 表示关闭提前刷新。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class CoCache(
    /**
     * 缓存名，默认取接口简单类名。
     */
    val name: String = "",
    /**
     * 命名空间（L1 key 与失效频道的一部分），默认等于缓存名。字符集为 `[A-Za-z0-9._-]+`，不含 `:`：前缀写在 [keyPrefix] 中。
     */
    val namespace: String = "",
    /**
     * L1 key 前缀，默认 `cocache:`。
     */
    val keyPrefix: String = "",
    /**
     * key 的 SpEL 模板表达式（如 `#{id}`，以业务 key 为根对象），为空时使用 `toString()`。
     */
    val keyExpression: String = "",
    val ttl: Long = UNSET,
    val ttlJitter: Long = UNSET,
    val absentTtl: Long = UNSET,
    /**
     * L2 副本的最长寿命：失效事件丢失时 L2 陈旧度的上界。
     */
    val localMaxAge: Long = UNSET,
    /**
     * 提前刷新窗口；`0` 表示关闭。
     */
    val refreshWindow: Long = UNSET,
    /**
     * 数据源 bean 名（`Cache` 与 `VersionedCache`）；为空时按泛型唯一匹配（`VersionedCache` 匹配 `CacheLoader<K, Versioned<V>>`）。
     * 权威类型（`Store`、`VersionedStore`、`SetStore`）没有数据源，设置时启动失败。
     */
    val loader: String = "",
    /**
     * `Cache` 的数据来源：[Source.LOADER]（默认）要求有数据源 bean，找不到时启动失败；[Source.EXTERNAL] 即 `externallySourced()`，
     * 由调用方经 `put`、Spring Cache 或调用级 loader 提供，不能与 [loader] 同时设置。其它类型只能是默认值。
     */
    val source: Source = Source.LOADER,
    /**
     * 编解码 bean 名；为空时按泛型 `Codec<V>` 唯一匹配，找不到则按值类型使用 Jackson。
     */
    val codec: String = "",
    /**
     * 不带 loader 的读取遇到 L1 读失败（`Lookup.Unknown`）时返回什么（RFC 0005 §4.1）：[UnknownRead.THROW]（默认）抛出
     * `CacheReadException`，`lookup` 返回 `Unknown`；[UnknownRead.ABSENT] 当作不存在，不写任何一层。
     * 只能用于没有数据源的读取：`Store`、`VersionedStore` 与 [Source.EXTERNAL] 的 `Cache`；其它接口设为 [UnknownRead.ABSENT] 时启动失败。
     */
    val unknownRead: UnknownRead = UnknownRead.THROW,
) {
    companion object {
        const val UNSET: Long = -1
        const val FOREVER: Long = Long.MAX_VALUE
    }

    /**
     * 派生缓存（`Cache`）的数据来源（RFC 0005 §4.1）。
     */
    enum class Source {
        /**
         * 数据源 bean：[CoCache.loader] 指定的，或按泛型唯一匹配的 `CacheLoader` / `AsyncCacheLoader`。
         */
        LOADER,

        /**
         * 外部来源（`externallySourced()`）：L1 中没有时是“未填充”，带 loader 的调用方用自己的 loader 回源。
         */
        EXTERNAL,
    }

    /**
     * L1 读失败时不带 loader 的读取的结果（[CoCache.unknownRead]，即 spec 的 `unknownRead`）。
     */
    enum class UnknownRead {
        /**
         * 抛出 `CacheReadException`（`get` / `getAll`），`lookup` 返回 `Lookup.Unknown`：没有答案时拒绝。
         */
        THROW,

        /**
         * 当作不存在（`Lookup.Absent`）：没有答案时放行。不写任何一层，`cocache.reads.unknown` 照常计数。
         */
        ABSENT,
    }
}

/**
 * 接口式声明的 L2（Caffeine）容量。[CoCache.UNSET] 表示使用默认值。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class CaffeineCache(
    val maximumSize: Long = CoCache.UNSET,
    val initialCapacity: Int = CoCache.UNSET.toInt(),
    /**
     * 空闲淘汰时长（秒）。每次读取都会写访问时间，热点高并发场景慎用。
     */
    val expireAfterAccess: Long = CoCache.UNSET,
)
