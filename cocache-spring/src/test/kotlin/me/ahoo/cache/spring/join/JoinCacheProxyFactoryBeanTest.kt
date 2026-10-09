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
import me.ahoo.cache.annotation.joinCacheMetadata
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.proxy.JoinCacheProxyFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext

class JoinCacheProxyFactoryBeanTest {
    interface TestJoinCache : JoinCache<String, String, String, String>

    @Test
    fun getObject() {
        val proxy = mockk<TestJoinCache>()
        val metadata = joinCacheMetadata<TestJoinCache>()
        val appContext = mockk<ApplicationContext> {
            every { getBean(JoinCacheProxyFactory::class.java) } returns mockk {
                every { create<TestJoinCache>(metadata) } returns proxy
            }
        }
        val factoryBean = JoinCacheProxyFactoryBean(metadata)
        factoryBean.setApplicationContext(appContext)

        factoryBean.getObject().assert().isSameAs(proxy)
        factoryBean.objectType.assert().isEqualTo(TestJoinCache::class.java)
    }
}
