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

package me.ahoo.cache.spring

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.client.SpringClientSideCacheFactory
import me.ahoo.cache.spring.converter.SpringKeyConverterFactory
import me.ahoo.cache.spring.source.SpringCacheSourceFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import java.util.function.Supplier

class CacheComponentFactoryTest {
    @CoCache(name = "first", keyPrefix = "\${prefix:p}:")
    interface FirstCache : Cache<String, String>

    @CoCache(name = "second")
    interface SecondCache : Cache<String, String>

    private val firstMetadata = coCacheMetadata<FirstCache>()
    private val secondMetadata = coCacheMetadata<SecondCache>()

    @Test
    fun clientSideCacheResolvedByNameOnly() {
        val named = MapClientSideCache<String>()
        val appContext = GenericApplicationContext().apply {
            registerBean("first.ClientSideCache", ClientSideCache::class.java, Supplier<ClientSideCache<*>> { named })
            refresh()
        }
        val factory = SpringClientSideCacheFactory(appContext)

        factory.create<String>(firstMetadata).assert().isSameAs(named)
        // 同类型的 bean 不会被其它缓存共享
        factory.create<String>(secondMetadata).assert().isInstanceOf(CaffeineClientSideCache::class.java)
    }

    @Test
    fun keyConverterResolvesPlaceholders() {
        val appContext = GenericApplicationContext().apply { refresh() }
        val factory = SpringKeyConverterFactory(appContext)

        factory.create<String>(firstMetadata).toStringKey("id").assert().isEqualTo("p:id")
        factory.create<String>(secondMetadata).toStringKey("id").assert().isEqualTo("cocache:second:id")
    }

    @Test
    fun keyConverterResolvedByName() {
        val named = ToStringKeyConverter<String>("named:")
        val appContext = GenericApplicationContext().apply {
            registerBean(
                "first.KeyConverter",
                ToStringKeyConverter::class.java,
                Supplier<ToStringKeyConverter<*>> { named }
            )
            refresh()
        }
        SpringKeyConverterFactory(appContext).create<String>(firstMetadata).assert().isSameAs(named)
    }

    class StringSource : CacheSource<String, String> {
        override fun loadCacheValue(key: String) = CacheValue.forever("loaded")
    }

    @Test
    fun cacheSourceResolvedByTypeWhenUnique() {
        val appContext = GenericApplicationContext().apply {
            registerBean("stringSource", StringSource::class.java, *emptyArray<Any?>())
            refresh()
        }
        SpringCacheSourceFactory(
            appContext
        ).create<String, String>(firstMetadata).assert().isInstanceOf(StringSource::class.java)
    }

    @Test
    fun cacheSourceFallsBackToNoOp() {
        val beanFactory = mockk<org.springframework.beans.factory.BeanFactory> {
            every { containsBean(any()) } returns false
            every { getBeanProvider<Any>(any<org.springframework.core.ResolvableType>()) } returns mockk {
                every { getIfUnique() } returns null
            }
        }
        SpringCacheSourceFactory(
            beanFactory
        ).create<String, String>(firstMetadata).loadCacheValue("id").assert().isNull()
    }
}
