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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.ResolvableType

/**
 * 已声明缓存的目录（#908 §2.3）：一次扫描得到每个声明的缓存名、bean 名与 API 类型；配置冻结之后按容器复用，定义数变化时重新扫描。
 */
class DeclaredCachesTest {
    @CoCache(name = "views")
    interface ViewCache : SuspendingCache<String, String>

    @CoCache(name = "tokens")
    interface TokenStore : AsyncStore<String, String>

    private fun beanFactory() = DefaultListableBeanFactory().apply {
        registerBeanDefinition("viewsBean", CacheBeanDefinitions.annotatedCacheDefinition(ViewCache::class.java))
        registerBeanDefinition("tokensBean", CacheBeanDefinitions.annotatedCacheDefinition(TokenStore::class.java))
        registerSingleton(
            "ordersSpec",
            cacheSpec<String, String>("orders") {
                codec = StringCodec
                externallySourced()
            }
        )
        registerSingleton("ledgerSpec", storeSpec<String, String>("ledger") { codec = StringCodec })
        listOf("ordersSpec", "ledgerSpec").forEach { spec ->
            registerBeanDefinition(
                "${spec}Cache",
                CacheBeanDefinitions.specCacheDefinition(ResolvableType.forClass(Cache::class.java), spec)
            )
        }
        registerBeanDefinition("other", RootBeanDefinition(String::class.java))
    }

    @Test
    fun entriesDescribeEveryDeclaration() {
        val caches = DeclaredCaches.of(beanFactory())
        caches.entries.map { Triple(it.cacheName, it.beanName, it.apiType) }.assert().containsExactly(
            Triple("views", "viewsBean", Cache::class.java),
            Triple("tokens", "tokensBean", Store::class.java),
            Triple("orders", "ordersSpecCache", Cache::class.java),
            Triple("ledger", "ledgerSpecCache", Store::class.java),
        )
        caches.names.assert().containsExactly("views", "tokens", "orders", "ledger")
        caches.derivedCacheBeans.assert().containsExactlyEntriesOf(
            mapOf("views" to "viewsBean", "orders" to "ordersSpecCache")
        )
        caches.underivedNames.assert().containsExactly("tokens", "ledger")
        caches.entries.filter { it.authoritative }.map { it.cacheName }.assert().containsExactly("tokens", "ledger")
        caches.entries.first().cacheInterface.assert().isEqualTo(ViewCache::class.java)
        caches.entries.last().spec?.name.assert().isEqualTo("ledger")
    }

    /**
     * 冻结之前每次都扫描；冻结之后复用同一份，定义数变化时重新扫描。
     */
    @Test
    fun theCatalogIsReusedOnceFrozen() {
        val beanFactory = beanFactory()
        DeclaredCaches.of(beanFactory).assert().isNotSameAs(DeclaredCaches.of(beanFactory))
        beanFactory.freezeConfiguration()
        val frozen = DeclaredCaches.of(beanFactory)
        DeclaredCaches.of(beanFactory).assert().isSameAs(frozen)
        beanFactory.registerSingleton("late", setStoreSpec<String, String>("late") { elementCodec = StringCodec })
        beanFactory.registerBeanDefinition(
            "lateCache",
            CacheBeanDefinitions.specCacheDefinition(ResolvableType.forClass(SetStore::class.java), "late")
        )
        val rescanned = DeclaredCaches.of(beanFactory)
        rescanned.assert().isNotSameAs(frozen)
        rescanned.names.assert().contains("late")
    }

    @Test
    fun apiTypesOfEverySpec() {
        val derived = cacheSpec<String, String>("a") {
            codec = StringCodec
            externallySourced()
        }
        ApiTypes.of(derived).assert().isEqualTo(Cache::class.java)
        val versioned = versionedCacheSpec<String, String>("b") {
            codec = StringCodec
            versionedLoader = me.ahoo.cocache.api.CacheLoader { me.ahoo.cocache.api.Versioned(it, 1) }
        }
        ApiTypes.of(versioned).assert().isEqualTo(VersionedCache::class.java)
        ApiTypes.of(storeSpec<String, String>("c") { codec = StringCodec }).assert().isEqualTo(Store::class.java)
        ApiTypes.of(
            versionedStoreSpec<String, String>("d") {
                codec = StringCodec
            }
        ).assert().isEqualTo(VersionedStore::class.java)
        ApiTypes.of(
            setStoreSpec<String, String>("e") { elementCodec = StringCodec }
        ).assert().isEqualTo(SetStore::class.java)
        ApiTypes.AUTHORITATIVE.assert().containsExactlyInAnyOrder(
            Store::class.java,
            VersionedStore::class.java,
            SetStore::class.java
        )
        ApiTypes.authoritative(Cache::class.java).assert().isFalse()
    }
}
