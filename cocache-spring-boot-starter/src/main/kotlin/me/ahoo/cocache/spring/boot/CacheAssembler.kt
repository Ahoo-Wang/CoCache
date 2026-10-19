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
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * 按声明创建缓存：依次叠加定制器与配置文件中的策略与 key 前缀（`key-prefix`），再交给 [CacheRuntime]。
 *
 * 所有单例创建完成后校验：配置文件中出现未声明的缓存名时启动失败（多半是拼写错误）。缓存名不区分大小写
 * （环境变量绑定的 key 是小写）；“已声明”包括尚未创建的缓存（延迟初始化的缓存 bean），由 [declaredCacheNames] 提供。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class CacheAssembler(
    private val runtime: CacheRuntime,
    private val customizers: List<CacheSpecCustomizer>,
    private val properties: CoCacheProperties,
    private val declaredCacheNames: () -> Set<String> = { emptySet() },
) : SmartInitializingSingleton {

    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> = withCacheName(spec.name) {
        val builder = spec.toBuilder()
        customizers.filter { it.supports(spec.name) }.forEach { it.customize(builder) }
        builder.policy = properties.policyFor(spec.name, builder.policy)
        properties.keyPrefixFor(spec.name)?.let { builder.keyPrefix = it }
        runtime.create(builder.build())
    }

    fun <K : Any, E : Any> create(spec: SetCacheSpec<K, E>): SetCache<K, E> = withCacheName(spec.name) {
        val builder = spec.toBuilder()
        customizers.filter { it.supports(spec.name) }.forEach { it.customize(builder) }
        builder.policy = properties.setPolicyFor(spec.name, builder.policy)
        properties.keyPrefixFor(spec.name)?.let { builder.keyPrefix = it }
        runtime.create(builder.build())
    }

    override fun afterSingletonsInstantiated() {
        val declared = runtime.cacheNames + declaredCacheNames()
        val unknown = properties.caches.keys.filter { key -> declared.none { it.equals(key, ignoreCase = true) } }
        check(unknown.isEmpty()) {
            "${CoCacheProperties.PREFIX}.caches configures undeclared caches $unknown; declared caches: ${declared.sorted()}."
        }
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
