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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.SimpleJoinCache
import me.ahoo.cache.join.proxy.JoinCacheProxyFactory
import me.ahoo.cache.proxy.CacheDelegated
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class JoinCacheProxyFactoryBeanTest {
    interface TestJoinCache : JoinCache<String, String, String, String>

    @Test
    fun destroyClosesComposedCaches() {
        val firstCloseCount = AtomicInteger(0)
        val joinCloseCount = AtomicInteger(0)
        val firstCache = newCountingCacheProxy(firstCloseCount)
        val joinCache = newCountingCacheProxy(joinCloseCount)
        val delegate = SimpleJoinCache(firstCache, joinCache) { _ -> "" }

        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TestJoinCache::class.java, CacheDelegated::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "getDelegate" -> delegate
                "equals" -> proxyInstance === args?.get(0)
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "TestJoinCacheProxy"
                else -> null
            }
        }

        val appContext = mockk<ApplicationContext>()
        val joinCacheProxyFactory = mockk<JoinCacheProxyFactory>()
        every { appContext.getBean(JoinCacheProxyFactory::class.java) } returns joinCacheProxyFactory
        every { joinCacheProxyFactory.create<TestJoinCache>(any()) } returns proxy as TestJoinCache

        val factoryBean = JoinCacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        factoryBean.getObject().assert().isSameAs(factoryBean.getObject())
        factoryBean.destroy()

        firstCloseCount.get().assert().isOne()
        joinCloseCount.get().assert().isOne()
    }

    @Test
    fun destroyWithoutGetObjectIsNoOp() {
        val factoryBean = JoinCacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.destroy()
    }

    /**
     * getObject() must keep returning the memoized proxy after destroy() — the
     * underlying composed caches must NOT be re-closed by a later getObject() call.
     */
    @Test
    fun getObjectAfterDestroyReturnsSameMemoizedProxy() {
        val firstCloseCount = AtomicInteger(0)
        val joinCloseCount = AtomicInteger(0)
        val firstCache = newCountingCacheProxy(firstCloseCount)
        val joinCache = newCountingCacheProxy(joinCloseCount)
        val delegate = SimpleJoinCache(firstCache, joinCache) { _ -> "" }

        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TestJoinCache::class.java, CacheDelegated::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "getDelegate" -> delegate
                "equals" -> proxyInstance === args?.get(0)
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "TestJoinCacheProxy"
                else -> null
            }
        }

        val appContext = mockk<ApplicationContext>()
        val joinCacheProxyFactory = mockk<JoinCacheProxyFactory>()
        every { appContext.getBean(JoinCacheProxyFactory::class.java) } returns joinCacheProxyFactory
        every { joinCacheProxyFactory.create<TestJoinCache>(any()) } returns proxy as TestJoinCache

        val factoryBean = JoinCacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        val first = factoryBean.getObject()
        factoryBean.destroy()
        val second = factoryBean.getObject()

        first.assert().isSameAs(second)
        firstCloseCount.get().assert().isOne()
        joinCloseCount.get().assert().isOne()
    }

    private fun newCountingCacheProxy(closeCount: AtomicInteger): Cache<String, String> {
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cache::class.java, AutoCloseable::class.java),
        ) { proxyInstance, method, _ ->
            when (method.name) {
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "equals" -> false
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "CountingCacheProxy"
                else -> null
            }
        } as Cache<String, String>
    }
}
