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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cocache.redis.StoreEvictionRisk
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory

/**
 * 五种用户 API 类型的角色（RFC 0005 §4.2，I-A）：权威副本（`Store`、`VersionedStore`、`SetStore`）持有唯一的副本，没有数据源；
 * 派生副本（`Cache`、`VersionedCache`）持有数据源可以重建的副本。starter 各处的“是不是权威类型”只看这里。
 */
internal object ApiTypes {
    /**
     * 规则只有一份，在 `cocache-redis` 的 [StoreEvictionRisk.authoritative]（驱逐检查不经 starter 也要用它）。
     */
    fun authoritative(apiType: Class<*>): Boolean = StoreEvictionRisk.authoritative(apiType)

    /**
     * spec 声明的缓存的 API 类型。
     */
    fun of(spec: Spec<*, *>): Class<*> = when (spec) {
        is CacheSpec<*, *> -> Cache::class.java
        is VersionedCacheSpec<*, *> -> VersionedCache::class.java
        is StoreSpec<*, *> -> Store::class.java
        is VersionedStoreSpec<*, *> -> VersionedStore::class.java
        is SetStoreSpec<*, *> -> SetStore::class.java
    }
}

/**
 * 一个容器中已声明（不论是否已创建）的缓存：starter 注册的缓存 bean 定义（接口式声明与 spec 声明，[CacheBeanDefinitions]）。
 * 名称校验（[CacheAssembler]）、Spring Cache 的检查与按名称创建（[SpringCacheSyncCheck]、`cocacheCacheManager`）、驱逐检查
 * （[StoreEvictionCheck]）与组合视图的错误提示都读这一份，不各自扫描 bean 定义。只看这一个容器的定义：组合视图的逐级遮蔽
 * （[JoinCacheFactory]）按容器逐级使用它。
 *
 * spec 声明的条目需要取出 spec bean（不可变数据，取出代价很小）；构建期（Spring AOT）不取 bean，不用它（[CoCacheAotProcessor]）。
 *
 * 每次调用都重新扫描，不缓存：读它的只有启动检查、Spring Cache 的按名称创建（一次）、驱逐检查与报错路径，一次扫描只是遍历一遍
 * bean 定义。也不能按容器放进静态表：spec 的 loader 常常持有 repository 或事务代理，它们又持有容器，弱键表的值强引用自己的键，
 * 关闭的容器就永远不会被回收。
 *
 * @property entries 按 bean 定义的顺序
 */
internal class DeclaredCaches private constructor(val entries: List<Entry>) {
    /**
     * 一个缓存声明。
     *
     * @property beanName 缓存 bean 的名称
     * @property apiType 五种用户 API 类型之一（视图接口取它所属的类型）
     * @property cacheInterface 接口式声明的接口；spec 声明为 `null`
     * @property spec spec 声明的 spec；接口式声明为 `null`
     */
    class Entry(
        val beanName: String,
        val cacheName: String,
        val apiType: Class<*>,
        val cacheInterface: Class<*>?,
        val spec: Spec<*, *>?,
    ) {
        val authoritative: Boolean
            get() = ApiTypes.authoritative(apiType)
    }

    /**
     * 已声明的缓存名。
     */
    val names: Set<String> = entries.mapTo(linkedSetOf()) { it.cacheName }

    /**
     * 派生副本 `Cache` 的声明：缓存名 → 缓存 bean 名（同名时取第一个）。
     */
    val derivedCacheBeans: Map<String, String> = linkedMapOf<String, String>().also { beans ->
        entries.filter { it.apiType == Cache::class.java }.forEach { beans.putIfAbsent(it.cacheName, it.beanName) }
    }

    /**
     * 不是派生副本 `Cache` 的缓存名（Spring Cache 不接入它们）。
     */
    val underivedNames: Set<String> = entries.filter { it.apiType != Cache::class.java }.mapTo(
        linkedSetOf()
    ) { it.cacheName }

    companion object {
        fun of(beanFactory: ConfigurableListableBeanFactory): DeclaredCaches {
            val entries = mutableListOf<Entry>()
            for (beanName in beanFactory.beanDefinitionNames) {
                val definition = beanFactory.getBeanDefinition(beanName)
                CacheBeanDefinitions.cacheInterfaceOf(definition)?.let { type ->
                    entries += Entry(beanName, AnnotatedCacheFactory.cacheNameOf(type), CacheProxies.apiTypeOf(type), type, null)
                }
                CacheBeanDefinitions.specBeanOf(definition)?.let { specBean ->
                    val spec = beanFactory.getBean(specBean) as Spec<*, *>
                    entries += Entry(beanName, spec.name, ApiTypes.of(spec), null, spec)
                }
            }
            return DeclaredCaches(entries)
        }
    }
}
