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

package me.ahoo.cache.join.proxy

import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.proxy.CoCacheProxy
import java.lang.reflect.Method
import kotlin.reflect.jvm.javaGetter

class JoinCacheInvocationHandler<DELEGATE>(
    override val cacheMetadata: JoinCacheMetadata,
    override val delegate: DELEGATE,
) : JoinCacheMetadataCapable, CoCacheProxy<DELEGATE>() where DELEGATE : JoinCache<*, *, *, *> {

    companion object {
        val CACHE_METADATA_METHOD_SIGN: String = JoinCacheMetadataCapable::cacheMetadata.javaGetter!!.name
    }

    override val proxyInterface: Class<*> = cacheMetadata.proxyInterface.java

    @Suppress("SpreadOperator", "ReturnCount")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        if (CACHE_METADATA_METHOD_SIGN == method.name) {
            return cacheMetadata
        }
        return super.invoke(proxy, method, args)
    }
}
