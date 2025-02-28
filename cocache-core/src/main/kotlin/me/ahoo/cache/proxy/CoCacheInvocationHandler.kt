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

package me.ahoo.cache.proxy

import me.ahoo.cache.Cache
import me.ahoo.cache.CacheValue
import me.ahoo.cache.NamedCache
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class CoCacheInvocationHandler<V : Any, DELEGATE>(private val delegate: DELEGATE) :
    InvocationHandler where DELEGATE : Cache<String, V>, DELEGATE : NamedCache {
    companion object {
        val GET_CACHE_SIGNATURE = Cache<*, *>::getCache.name
        val SET_CACHE_SIGNATURE = Cache<*, *>::setCache.name
        val EVICT_SIGNATURE = Cache<*, *>::evict.name
        val CACHE_NAME_SIGNATURE = NamedCache::cacheName.getter.name
    }

    @Suppress("UNCHECKED_CAST", "SpreadOperator", "ReturnCount")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>): Any? {
        if (method.isDefault) {
            return method.invoke(delegate, *args)
        }
        when (method.name) {
            GET_CACHE_SIGNATURE -> {
                val key = args[0] as String
                return delegate.getCache(key)
            }

            SET_CACHE_SIGNATURE -> {
                val key = args[0] as String
                val value = args[1] as CacheValue<V>
                return delegate.setCache(key, value)
            }

            EVICT_SIGNATURE -> {
                val key = args[0] as String
                delegate.evict(key)
                return Unit
            }

            CACHE_NAME_SIGNATURE -> {
                return delegate.cacheName
            }

            else -> {
                throw IllegalArgumentException("Unsupported method: ${method.name}.")
            }
        }
    }
}
