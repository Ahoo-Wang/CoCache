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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.proxy.CacheMethods
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cocache.core.proxy.DefaultMethods

/**
 * 缓存的装配入口（RFC 0005 §4.8）：starter 与库经它创建缓存。声明与库的锁定（[Locks]）先经 [layers] 变成生效声明
 * （starter 中是三层配置与定制器），再交给 [runtime] 创建；这样创建的缓存是“已分层”的（[CacheAdmin.layered]），
 * [CacheAdmin.policyReport] 给出每个字段的生效值与来源。直接经 [CacheRuntime.create] 创建的缓存不经过任何一层，
 * 端点与启动日志标注“未分层”（S20）：运维在配置文件中写下的值对它静默不生效，所以库应经本类创建缓存、用 [Locks] 固定策略。
 *
 * 接口式声明经 `create(type, spec)`：`type` 必须是继承与 spec 对应的用户 API 类型、或它的异步与挂起视图的接口（RFC 0005 §4.2，S21），
 * 代理把调用转发给缓存或它的视图，异常原样抛出。用户 API 接口不供手写实现（委托除外）：需要自己的类时，经 `create(spec)` 创建缓存再委托给它。
 *
 * 锁定（U2、S12）：`create(spec) { lock(PolicyField.TTL); constrain(PolicyField.TTL) { it.finiteOr(…) } }`，锁定者默认是调用方的类名；
 * Java 经 `create(spec, Locks.builder("my-lib")….build())`。
 *
 * @param runtime 缓存所在的运行时
 * @param layers 声明到生效声明的装配层；默认 [CacheLayers.NONE]，只有声明一层
 */
class CoCacheFactory @JvmOverloads constructor(
    val runtime: CacheRuntime,
    private val layers: CacheLayers = CacheLayers.NONE,
) {
    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> = create(spec, Locks.NONE)

    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>, locks: Locks): Cache<K, V> {
        val layered = checked(spec, locks, layers.apply(spec, locks))
        return runtime.create(layered.spec, layered.report)
    }

    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>, block: Locks.Builder.() -> Unit): Cache<K, V> =
        create(spec, locksOf(block))

    fun <K : Any, V : Any> create(spec: VersionedCacheSpec<K, V>): VersionedCache<K, V> = create(spec, Locks.NONE)

    fun <K : Any, V : Any> create(spec: VersionedCacheSpec<K, V>, locks: Locks): VersionedCache<K, V> {
        val layered = checked(spec, locks, layers.apply(spec, locks))
        return runtime.create(layered.spec, layered.report)
    }

    fun <K : Any, V : Any> create(
        spec: VersionedCacheSpec<K, V>,
        block: Locks.Builder.() -> Unit
    ): VersionedCache<K, V> = create(spec, locksOf(block))

    fun <K : Any, V : Any> create(spec: StoreSpec<K, V>): Store<K, V> = create(spec, Locks.NONE)

    fun <K : Any, V : Any> create(spec: StoreSpec<K, V>, locks: Locks): Store<K, V> {
        val layered = checked(spec, locks, layers.apply(spec, locks))
        return runtime.create(layered.spec, layered.report)
    }

    fun <K : Any, V : Any> create(spec: StoreSpec<K, V>, block: Locks.Builder.() -> Unit): Store<K, V> =
        create(spec, locksOf(block))

    fun <K : Any, V : Any> create(spec: VersionedStoreSpec<K, V>): VersionedStore<K, V> = create(spec, Locks.NONE)

    fun <K : Any, V : Any> create(spec: VersionedStoreSpec<K, V>, locks: Locks): VersionedStore<K, V> {
        val layered = checked(spec, locks, layers.apply(spec, locks))
        return runtime.create(layered.spec, layered.report)
    }

    fun <K : Any, V : Any> create(
        spec: VersionedStoreSpec<K, V>,
        block: Locks.Builder.() -> Unit
    ): VersionedStore<K, V> = create(spec, locksOf(block))

    fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>): SetStore<K, E> = create(spec, Locks.NONE)

    fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>, locks: Locks): SetStore<K, E> {
        val layered = checked(spec, locks, layers.apply(spec, locks))
        return runtime.create(layered.spec, layered.report)
    }

    fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>, block: Locks.Builder.() -> Unit): SetStore<K, E> =
        create(spec, locksOf(block))

    /**
     * 按 spec 的类型创建对应的缓存（见各重载）。
     */
    @JvmOverloads
    fun create(spec: Spec<*, *>, locks: Locks = Locks.NONE): ReadableCache<*, *> = when (spec) {
        is CacheSpec<*, *> -> create(spec, locks)
        is VersionedCacheSpec<*, *> -> create(spec, locks)
        is StoreSpec<*, *> -> create(spec, locks)
        is VersionedStoreSpec<*, *> -> create(spec, locks)
        is SetStoreSpec<*, *> -> create(spec, locks)
    }

    /**
     * 按 spec 创建缓存，返回实现接口 [type] 的代理（接口式声明，如 `interface RevokedTokens : Store<String, Instant>`，
     * 或挂起视图 `interface UserCache : SuspendingCache<String, User>`）。
     * 创建缓存之前校验：[type] 是接口，恰好继承一个可声明的类型（`CacheProxies.DECLARABLE_TYPES`：用户 API 类型或它的 `Async*`、
     * `Suspending*` 视图），它对应的用户 API 类型与 spec 的类型一致，接口中重新声明的抽象方法都能转发（否则改为 default 方法）。
     * 类型不符、或 default 方法所在的包可见接口不能访问（具名模块没有把包开放给 CoCache）时抛出 [IllegalArgumentException]，
     * 抽象方法不能转发时抛出 [IllegalStateException]，都不创建任何东西。
     *
     * 视图接口的代理转发给缓存的 `async()` / `suspending()` 视图；挂起视图走协程核心的原生路径。泛型参数不在运行时检查：
     * `K`、`V` 由 spec 与接口的声明保证一致。
     */
    @JvmOverloads
    fun <C : Any> create(type: Class<C>, spec: Spec<*, *>, locks: Locks = Locks.NONE): C {
        require(type.isInterface) {
            "Cache[${spec.name}] - CoCacheFactory.create(type, spec) needs an interface, but ${type.name} is a class. " +
                "The user API types are not for hand-written implementations: create the cache with create(spec) " +
                "and delegate to it."
        }
        val declaredType = CacheProxies.declaredTypeOf(type)
        val apiType = CacheProxies.DECLARABLE_TYPES.getValue(declaredType)
        val declared = apiTypeOf(spec)
        require(apiType == declared) {
            "Cache[${spec.name}] - interface[${type.name}] extends ${declaredType.simpleName}, " +
                "but its spec declares a ${declared.simpleName}."
        }
        CacheMethods.validate(type, declaredType)
        val defaults = DefaultMethods.of(type)
        return CacheProxies.create(type, create(spec, locks), defaults)
    }

    fun <C : Any> create(type: Class<C>, spec: Spec<*, *>, block: Locks.Builder.() -> Unit): C =
        create(type, spec, locksOf(block))

    override fun toString(): String = "CoCacheFactory(layers=$layers)"

    private fun <S : Spec<*, *>> checked(declared: Spec<*, *>, locks: Locks, layered: Layered<S>): Layered<S> {
        require(layered.spec.name == declared.name) {
            "Cache[${declared.name}] - CacheLayers must not rename a cache, but $layers returned Cache[${layered.spec.name}]."
        }
        locks.verify(layered.spec)
        return layered
    }

    private companion object {
        fun apiTypeOf(spec: Spec<*, *>): Class<*> = when (spec) {
            is CacheSpec<*, *> -> Cache::class.java
            is VersionedCacheSpec<*, *> -> VersionedCache::class.java
            is StoreSpec<*, *> -> Store::class.java
            is VersionedStoreSpec<*, *> -> VersionedStore::class.java
            is SetStoreSpec<*, *> -> SetStore::class.java
        }

        /**
         * DSL 的锁定，锁定者是调用 `create` 的类（栈上 `CoCacheFactory` 之外的第一个类）。
         */
        fun locksOf(block: Locks.Builder.() -> Unit): Locks = Locks.builder(callerName()).apply(block).build()

        private fun callerName(): String = StackWalker.getInstance().walk { frames ->
            frames.map { it.className }
                .filter { it != CoCacheFactory::class.java.name && !it.startsWith(CoCacheFactory::class.java.name + "$") }
                .findFirst()
                .orElse("unknown")
        }
    }
}
