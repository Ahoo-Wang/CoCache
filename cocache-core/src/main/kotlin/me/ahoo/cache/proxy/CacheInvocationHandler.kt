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

import me.ahoo.cache.api.Cache
import me.ahoo.cache.join.proxy.JoinCacheMetadataCapable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 缓存接口代理的统一调度：
 *
 * - [CacheDelegated] / 元数据访问器 → 直接返回；
 * - `Object` 方法 → 按代理对象身份语义处理；
 * - 用户接口的默认方法（委托对象未实现其声明接口）→ 调用默认实现；
 * - 其余方法 → 转发给 [delegate]，并解包 [InvocationTargetException]，使调用方收到原始异常。
 */
class CacheInvocationHandler(
    private val proxyInterface: Class<*>,
    private val delegate: Cache<*, *>,
    private val cacheMetadata: Any
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val declaringClass = method.declaringClass
        return when {
            declaringClass == CacheDelegated::class.java -> delegate
            declaringClass == CacheMetadataCapable::class.java ||
                declaringClass == JoinCacheMetadataCapable::class.java -> cacheMetadata

            declaringClass == Any::class.java -> invokeObjectMethod(proxy, method, args)
            method.isDefault && !declaringClass.isInstance(delegate) -> {
                InvocationHandler.invokeDefault(proxy, method, *(args ?: EMPTY_ARGS))
            }

            else -> invokeDelegate(method, args)
        }
    }

    private fun invokeObjectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any {
        return when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            else -> "${proxyInterface.simpleName}(delegate=$delegate)"
        }
    }

    private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? {
        try {
            return method.invoke(delegate, *(args ?: EMPTY_ARGS))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        val EMPTY_ARGS = emptyArray<Any?>()
    }
}
