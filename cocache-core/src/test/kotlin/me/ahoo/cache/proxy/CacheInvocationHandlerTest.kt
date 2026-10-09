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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.api.Cache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class CacheInvocationHandlerTest {
    interface GreetingCache : Cache<String, String> {
        fun greet(name: String): String = "hello $name from ${get(name)}"
    }

    private val delegate = mockk<Cache<String, String>>().also {
        every { it["cocache"] } returns "delegate"
    }

    private fun proxy(): GreetingCache {
        return Proxy.newProxyInstance(
            GreetingCache::class.java.classLoader,
            arrayOf(GreetingCache::class.java),
            CacheInvocationHandler(GreetingCache::class.java, delegate, Any()),
        ) as GreetingCache
    }

    @Test
    fun defaultMethodOfProxyInterfaceRunsOnProxy() {
        // 代理接口自身的默认方法委托对象并未实现：在代理上执行，内部调用仍分派给委托对象
        proxy().greet("cocache").assert().isEqualTo("hello cocache from delegate")
    }

    @Test
    fun objectMethodsUseProxyIdentity() {
        val proxy = proxy()
        (proxy == proxy).assert().isTrue()
        val absent: Any? = null
        proxy.equals(absent).assert().isFalse()
        (proxy == proxy()).assert().isFalse()
        proxy.hashCode().assert().isEqualTo(System.identityHashCode(proxy))
        proxy.toString().assert().startsWith("GreetingCache(delegate=")
    }
}
