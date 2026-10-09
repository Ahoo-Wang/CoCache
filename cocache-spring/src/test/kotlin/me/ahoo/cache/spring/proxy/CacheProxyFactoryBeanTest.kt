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

package me.ahoo.cache.spring.proxy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cache.api.Cache
import me.ahoo.cache.proxy.CacheProxyFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext

class CacheProxyFactoryBeanTest {
    interface CloseableCache : Cache<String, String>, AutoCloseable

    @Test
    fun getObjectCreatesOnceAndDestroyClosesProxy() {
        val proxy = mockk<CloseableCache>(relaxUnitFun = true)
        val cacheProxyFactory = mockk<CacheProxyFactory> {
            every { create<CloseableCache>(any()) } returns proxy
        }
        val appContext = mockk<ApplicationContext> {
            every { getBean(CacheProxyFactory::class.java) } returns cacheProxyFactory
        }
        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        factoryBean.getObject().assert().isSameAs(factoryBean.getObject())
        factoryBean.destroy()

        verify(exactly = 1) { cacheProxyFactory.create<CloseableCache>(any()) }
        verify(exactly = 1) { proxy.close() }
    }

    @Test
    fun destroyWithoutGetObjectIsNoOp() {
        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.destroy()
    }
}
