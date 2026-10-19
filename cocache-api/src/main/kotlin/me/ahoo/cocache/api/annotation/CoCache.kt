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
 * 注解只是声明的另一种写法：启动时翻译为 `CacheSpec`，与 spec bean 走同一个引擎；
 * `CacheSpecCustomizer` 与配置文件 `cocache.caches.[name].*` 照常生效（优先级高于注解）。
 *
 * 时长单位均为秒；[UNSET] 表示未设置（使用默认值或配置文件），[FOREVER] 表示永不过期（仅用于 [ttl]）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class CoCache(
    /**
     * 缓存名，默认取接口简单类名（与 5.x 相同）。
     */
    val name: String = "",
    /**
     * 命名空间（L1 key 与失效频道的一部分），默认等于缓存名。
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
     * 提前刷新窗口。
     */
    val refreshWindow: Long = UNSET,
    /**
     * 数据源 bean 名；为空时按泛型 `CacheLoader<K, V>` 唯一匹配，找不到则没有数据源。
     */
    val loader: String = "",
    /**
     * 编解码 bean 名；为空时按泛型 `Codec<V>` 唯一匹配，找不到则按值类型使用 Jackson。
     */
    val codec: String = "",
) {
    companion object {
        const val UNSET: Long = -1
        const val FOREVER: Long = Long.MAX_VALUE
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
