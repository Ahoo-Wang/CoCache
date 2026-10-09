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
import org.springframework.beans.factory.FactoryBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

/**
 * JoinCache 代理不持有组件缓存的生命周期（组件缓存由各自的 FactoryBean 关闭）。
 */
class JoinCacheProxyFactoryBean(private val cacheMetadata: JoinCacheMetadata) :
    FactoryBean<JoinCache<Any, Any, Any, Any>>,
    ApplicationContextAware {
    private lateinit var appContext: ApplicationContext

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.appContext = applicationContext
    }

    override fun getObject(): JoinCache<Any, Any, Any, Any> {
        return appContext.getBean(JoinCacheProxyFactory::class.java).create(cacheMetadata)
    }

    override fun getObjectType(): Class<*> {
        return cacheMetadata.proxyInterface.java
    }
}
