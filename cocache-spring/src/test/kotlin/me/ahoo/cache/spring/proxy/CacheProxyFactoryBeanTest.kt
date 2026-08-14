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
import me.ahoo.cache.api.Cache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.proxy.CacheProxyFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class CacheProxyFactoryBeanTest {
    interface TestCache : Cache<String, String>

    @Test
    fun getObjectCreatesOnceAndDestroyClosesProxy() {
        val closeCount = AtomicInteger(0)
        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TestCache::class.java, CoherentCache::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "equals" -> proxyInstance === args?.get(0)
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "TestCacheProxy"
                else -> null
            }
        } as TestCache

        val appContext = mockk<ApplicationContext>()
        val cacheProxyFactory = mockk<CacheProxyFactory>()
        every { appContext.getBean(CacheProxyFactory::class.java) } returns cacheProxyFactory
        every { cacheProxyFactory.create<TestCache>(any()) } returns proxy

        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        factoryBean.getObject().assert().isSameAs(factoryBean.getObject())
        factoryBean.destroy()

        closeCount.get().assert().isOne()
    }

    @Test
    fun destroyWithoutGetObjectIsNoOp() {
        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.destroy()
    }

    /**
     * getObject() must keep returning the memoized proxy after destroy(); the proxy
     * reference is not invalidated, so subsequent calls return the same instance and
     * the close() side effect is not repeated.
     */
    @Test
    fun getObjectAfterDestroyReturnsSameMemoizedProxy() {
        val closeCount = AtomicInteger(0)
        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TestCache::class.java, CoherentCache::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "equals" -> proxyInstance === args?.get(0)
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "TestCacheProxy"
                else -> null
            }
        } as TestCache

        val appContext = mockk<ApplicationContext>()
        val cacheProxyFactory = mockk<CacheProxyFactory>()
        every { appContext.getBean(CacheProxyFactory::class.java) } returns cacheProxyFactory
        every { cacheProxyFactory.create<TestCache>(any()) } returns proxy

        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        val first = factoryBean.getObject()
        factoryBean.destroy()
        val second = factoryBean.getObject()

        first.assert().isSameAs(second)
        closeCount.get().assert().isOne()
    }
}
