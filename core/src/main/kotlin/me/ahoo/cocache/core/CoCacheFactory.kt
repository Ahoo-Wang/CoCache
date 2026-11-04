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

/**
 * 缓存的装配入口（RFC 0005 §4.8）：starter 与库经它创建缓存。声明先经 [layers] 变成生效声明（starter 中是定制器与配置文件），
 * 再交给 [runtime] 创建；这样创建的缓存是“已分层”的（[CacheAdmin.layered]）。直接经 [CacheRuntime.create] 创建的缓存不经过
 * 任何一层，端点与启动日志标注“未分层”（S20）：运维在配置文件中写下的值对它静默不生效，所以库应经本类创建缓存。
 *
 * 接口式声明经 [create]（`type, spec`）：`type` 必须是继承与 spec 对应的用户 API 类型的接口（RFC 0005 §4.2，S21），代理把调用
 * 转发给缓存，异常原样抛出。用户 API 接口不供手写实现（委托除外）：需要自己的类时，经 `create(spec)` 创建缓存再委托给它。
 *
 * 策略字段与存储布局的锁定（`lock` / `constrain`）随 M6 加在本类上，随声明交给 [layers]。
 *
 * @param runtime 缓存所在的运行时
 * @param layers 声明到生效声明的装配层；默认 [CacheLayers.NONE]，不做任何修改
 */
class CoCacheFactory @JvmOverloads constructor(
    val runtime: CacheRuntime,
    private val layers: CacheLayers = CacheLayers.NONE,
) {
    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> =
        runtime.create(sameName(spec, layers.apply(spec)), layered = true)

    fun <K : Any, V : Any> create(spec: VersionedCacheSpec<K, V>): VersionedCache<K, V> =
        runtime.create(sameName(spec, layers.apply(spec)), layered = true)

    fun <K : Any, V : Any> create(spec: StoreSpec<K, V>): Store<K, V> =
        runtime.create(sameName(spec, layers.apply(spec)), layered = true)

    fun <K : Any, V : Any> create(spec: VersionedStoreSpec<K, V>): VersionedStore<K, V> =
        runtime.create(sameName(spec, layers.apply(spec)), layered = true)

    fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>): SetStore<K, E> =
        runtime.create(sameName(spec, layers.apply(spec)), layered = true)

    /**
     * 按 spec 的类型创建对应的缓存（见各重载）。
     */
    fun create(spec: Spec<*, *>): ReadableCache<*, *> = when (spec) {
        is CacheSpec<*, *> -> create(spec)
        is VersionedCacheSpec<*, *> -> create(spec)
        is StoreSpec<*, *> -> create(spec)
        is VersionedStoreSpec<*, *> -> create(spec)
        is SetStoreSpec<*, *> -> create(spec)
    }

    /**
     * 按 spec 创建缓存，返回实现接口 [type] 的代理（接口式声明，如 `interface RevokedTokens : Store<String, Instant>`）。
     * 创建缓存之前校验：[type] 是接口，恰好继承一个用户 API 类型，它与 spec 的类型一致，接口中重新声明的抽象方法都能转发给缓存
     * （否则改为 default 方法）。类型不符时抛出 [IllegalArgumentException]，抽象方法不能转发时抛出 [IllegalStateException]，
     * 都不创建任何东西。
     *
     * 泛型参数不在运行时检查：`K`、`V` 由 spec 与接口的声明保证一致。
     */
    fun <C : ReadableCache<*, *>> create(type: Class<C>, spec: Spec<*, *>): C {
        require(type.isInterface) {
            "Cache[${spec.name}] - CoCacheFactory.create(type, spec) needs an interface, but ${type.name} is a class. " +
                "The user API types are not for hand-written implementations: create the cache with create(spec) " +
                "and delegate to it."
        }
        val apiType = CacheProxies.apiTypeOf(type)
        val declared = apiTypeOf(spec)
        require(apiType == declared) {
            "Cache[${spec.name}] - interface[${type.name}] extends ${apiType.simpleName}, " +
                "but its spec declares a ${declared.simpleName}."
        }
        CacheMethods.validate(type, apiType)
        return CacheProxies.create(type, create(spec))
    }

    override fun toString(): String = "CoCacheFactory(layers=$layers)"

    private fun <S : Spec<*, *>> sameName(declared: Spec<*, *>, effective: S): S {
        require(effective.name == declared.name) {
            "Cache[${declared.name}] - CacheLayers must not rename a cache, but $layers returned Cache[${effective.name}]."
        }
        return effective
    }

    private companion object {
        fun apiTypeOf(spec: Spec<*, *>): Class<*> = when (spec) {
            is CacheSpec<*, *> -> Cache::class.java
            is VersionedCacheSpec<*, *> -> VersionedCache::class.java
            is StoreSpec<*, *> -> Store::class.java
            is VersionedStoreSpec<*, *> -> VersionedStore::class.java
            is SetStoreSpec<*, *> -> SetStore::class.java
        }
    }
}
