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
import me.ahoo.cache.api.NamedCache
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import kotlin.reflect.jvm.javaGetter

class CoCacheInvocationHandler<K, V : Any, DELEGATE>(override val delegate: DELEGATE) :
    CacheDelegated<DELEGATE>,
    InvocationHandler where DELEGATE : Cache<K, V>, DELEGATE : NamedCache {

    companion object {
        val DELEGATE_METHOD_SIGN: String = CacheDelegated<*>::delegate.javaGetter!!.name
    }

    @Suppress("SpreadOperator", "ReturnCount")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        if (DELEGATE_METHOD_SIGN == method.name) {
            return delegate
        }
        if (args == null) {
            return method.invoke(delegate)
        }
        return method.invoke(delegate, *args)
    }
}
