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
import me.ahoo.cache.proxy.CoCacheInvocationHandler.Companion.EMPTY_ARGS
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

abstract class CoCacheProxy<K, V : Any, DELEGATE> :
    InvocationHandler,
    CacheDelegated<DELEGATE> where DELEGATE : Cache<K, V> {
    abstract val proxyInterface: Class<*>

    private val declaredDefaultMethods by lazy {
        proxyInterface.declaredMethods.filter { it.isDefault }
    }

    @Suppress("SpreadOperator")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodArgs = args ?: EMPTY_ARGS
        if (method.isDefault && declaredDefaultMethods.contains(method)) {
            return InvocationHandler.invokeDefault(proxy, method, *methodArgs)
        }
        return method.invoke(delegate, *methodArgs)
    }
}
