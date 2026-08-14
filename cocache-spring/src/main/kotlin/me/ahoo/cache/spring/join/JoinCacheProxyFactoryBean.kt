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

package me.ahoo.cache.spring.join

import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.proxy.JoinCacheProxyFactory
import me.ahoo.cache.proxy.CacheDelegated
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

class JoinCacheProxyFactoryBean(private val cacheMetadata: JoinCacheMetadata) :
    FactoryBean<JoinCache<Any, Any, Any, Any>>,
    ApplicationContextAware,
    DisposableBean {
    private lateinit var appContext: ApplicationContext
    private var proxy: JoinCache<Any, Any, Any, Any>? = null
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.appContext = applicationContext
    }

    override fun getObject(): JoinCache<Any, Any, Any, Any> {
        if (proxy == null) {
            val joinCacheProxyFactory = appContext.getBean(JoinCacheProxyFactory::class.java)
            proxy = joinCacheProxyFactory.create(cacheMetadata)
        }
        return requireNotNull(proxy)
    }

    override fun getObjectType(): Class<*> {
        return cacheMetadata.proxyInterface.java
    }

    override fun destroy() {
        val delegate = (proxy as? CacheDelegated<*>)?.delegate
        (delegate as? AutoCloseable)?.close()
    }
}
