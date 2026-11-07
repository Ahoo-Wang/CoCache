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

import me.ahoo.cocache.core.CacheLayers
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Layered
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * starter 的装配层（[CacheLayers]，RFC 0005 §4.8）：三层配置与定制器逐字段合并（[PolicyLayering]），并叠加 `key-prefix`。
 * starter 的 [CoCacheFactory] bean 以它创建缓存，接口式声明、spec bean 与经 `CoCacheFactory` 创建缓存的库都经过它。
 *
 * 所有单例创建完成后校验配置文件中的缓存名：未声明的名称按 `cocache.caches-validation` 启动失败（默认）或告警（多半是拼写错误）。
 * 缓存名不区分大小写（环境变量绑定的 key 是小写）；“已声明”包括尚未创建的缓存（延迟初始化的缓存 bean），由 [declaredCacheNames] 提供，
 * 以及库经 [CacheNameContributor] 贡献的、可能在运行时才创建的缓存名（[contributedCacheNames]，在校验时才读取）。
 * 指向未分层的缓存（直接经 `CacheRuntime.create` 创建，S20）的条目总是只告警（[UnlayeredConfigurationCheck]）。
 * 写事务内回源的两个机制按缓存的生效值登记到 `fillModes`（有 spring-tx、使用 starter 的回源钩子时）：分层时暂存，
 * 缓存创建成功时（运行时的创建监听）才生效，失败的创建不改写已有缓存的模式（#842）；生效之前开始的调用整次取类型默认值（#868）。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class CacheAssembler(
    private val runtime: CacheRuntime,
    customizers: List<CacheSpecCustomizer>,
    private val properties: CoCacheProperties,
    private val declaredCacheNames: () -> Set<String> = { emptySet() },
    fillModes: TransactionalFillModes? = null,
    private val contributedCacheNames: () -> Set<String> = { emptySet() },
) : CacheLayers, SmartInitializingSingleton {
    private val layering = PolicyLayering(properties, customizers, fillModes)

    init {
        fillModes?.let { modes -> runtime.addCreationListener { modes.commit(it) } }
    }

    override fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>, locks: Locks): Layered<CacheSpec<K, V>> =
        withCacheName(spec.name) { layering.layer(spec, locks, CacheSpecAdapter()) }

    override fun <K : Any, V : Any> apply(spec: VersionedCacheSpec<K, V>, locks: Locks): Layered<VersionedCacheSpec<K, V>> =
        withCacheName(spec.name) { layering.layer(spec, locks, VersionedCacheSpecAdapter()) }

    override fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
        withCacheName(spec.name) { layering.layer(spec, locks, StoreSpecAdapter()) }

    override fun <K : Any, V : Any> apply(spec: VersionedStoreSpec<K, V>, locks: Locks): Layered<VersionedStoreSpec<K, V>> =
        withCacheName(spec.name) { layering.layer(spec, locks, VersionedStoreSpecAdapter()) }

    override fun <K : Any, E : Any> apply(spec: SetStoreSpec<K, E>, locks: Locks): Layered<SetStoreSpec<K, E>> =
        withCacheName(spec.name) { layering.layer(spec, locks, SetStoreSpecAdapter()) }

    /**
     * 生效声明：叠加定制器与配置文件之后、交给运行时之前的 spec（不带库的锁定）。驱逐检查也用它得到尚未创建的权威缓存的 `ttl`。
     */
    internal fun resolve(spec: Spec<*, *>): Spec<*, *> = when (spec) {
        is CacheSpec<*, *> -> apply(spec, Locks.NONE).spec
        is VersionedCacheSpec<*, *> -> apply(spec, Locks.NONE).spec
        is StoreSpec<*, *> -> apply(spec, Locks.NONE).spec
        is VersionedStoreSpec<*, *> -> apply(spec, Locks.NONE).spec
        is SetStoreSpec<*, *> -> apply(spec, Locks.NONE).spec
    }

    override fun afterSingletonsInstantiated() {
        val declared = runtime.cacheNames + declaredCacheNames() + contributedCacheNames()
        val unknown = properties.caches.keys.filter { key -> declared.none { it.equals(key, ignoreCase = true) } }
        if (unknown.isEmpty()) {
            return
        }
        val message = "${CoCacheProperties.PREFIX}.caches configures undeclared caches $unknown; declared caches: ${declared.sorted()}. " +
            "A library that creates a cache at runtime declares its name with a ${CacheNameContributor::class.java.simpleName} bean."
        check(properties.cachesValidation == CoCacheProperties.Validation.WARN) { message }
        log.warn(message)
    }

    private companion object {
        private val log = LogFactory.getLog(CacheAssembler::class.java)
    }
}

/**
 * 策略校验等参数错误带上缓存名：`Cache[name] - …`。
 */
internal inline fun <T> withCacheName(cacheName: String, block: () -> T): T {
    try {
        return block()
    } catch (e: IllegalArgumentException) {
        val prefix = "Cache[$cacheName] - "
        if (e.message?.startsWith(prefix) == true) {
            throw e
        }
        throw IllegalArgumentException(prefix + e.message, e)
    }
}
