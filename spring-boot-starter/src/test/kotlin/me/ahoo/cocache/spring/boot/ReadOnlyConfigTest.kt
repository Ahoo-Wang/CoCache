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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 只读的声明经 starter 生效：`@CoCache(readOnly = true)`、`cocache.caches.[name].read-only`（只能写在这一节，优先于声明，`false` 撤销声明的只读）；
 * 派生类型声明只读时启动失败（属性按 `caches-validation`）；只读的缓存拒绝端点的写操作（400），读取照常。
 */
class ReadOnlyConfigTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    @CoCache(name = "ro-store", readOnly = true)
    interface ReadOnlyStore : Store<String, String>

    @CoCache(name = "ro-versioned", readOnly = true)
    interface ReadOnlyVersionedStore : VersionedStore<String, String>

    @CoCache(name = "ro-set", readOnly = true)
    interface ReadOnlySetStore : SetStore<String, String>

    @CoCache(name = "rw-store")
    interface ReadWriteStore : Store<String, String>

    @CoCache(name = "ro-cache", readOnly = true)
    interface ReadOnlyCache : Cache<String, String>

    @CoCache(name = "ro-versioned-cache", readOnly = true)
    interface ReadOnlyVersionedCache : VersionedCache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(
        caches = [ReadOnlyStore::class, ReadOnlyVersionedStore::class, ReadOnlySetStore::class, ReadWriteStore::class]
    )
    class AnnotatedConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()
    }

    @Configuration(proxyBeanMethods = false)
    class SpecConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun docSpec(): StoreSpec<String, String> = storeSpec("doc") { codec = StringCodec }

        @Bean
        fun declaredSpec(): StoreSpec<String, String> = storeSpec("declared") {
            codec = StringCodec
            readOnly = true
        }

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "v-$it" }
        }
    }

    @Test
    fun annotationDeclaresReadOnlyStores() {
        runner.withUserConfiguration(AnnotatedConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val factory = context.getBean(AnnotatedCacheFactory::class.java)
            (factory.specOf(ReadOnlyStore::class.java) as StoreSpec<*, *>).readOnly.assert().isTrue()
            (factory.specOf(ReadOnlyVersionedStore::class.java) as VersionedStoreSpec<*, *>).readOnly.assert().isTrue()
            (factory.specOf(ReadOnlySetStore::class.java) as SetStoreSpec<*, *>).readOnly.assert().isTrue()
            (factory.specOf(ReadWriteStore::class.java) as StoreSpec<*, *>).readOnly.assert().isFalse()
            val runtime = context.getBean(CacheRuntime::class.java)
            context.getBean(ReadOnlyStore::class.java)
            context.getBean(ReadWriteStore::class.java)
            requireNotNull(runtime.admin("ro-store")).readOnly.assert().isTrue()
            requireNotNull(runtime.admin("rw-store")).readOnly.assert().isFalse()
            runCatching { context.getBean(ReadOnlyStore::class.java).put("k", "v") }.exceptionOrNull()
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
            listOf(ReadOnlyCache::class.java, ReadOnlyVersionedCache::class.java).forEach { type ->
                runCatching { factory.specOf(type) }.exceptionOrNull()
                    .assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("@CoCache(readOnly = true) is not supported")
                    .hasMessageContaining(type.name)
            }
        }
    }

    @Test
    fun perCachePropertySetsAndRevokesReadOnly() {
        runner.withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues("cocache.caches.doc.read-only=true", "cocache.caches.declared.read-only=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)

                @Suppress("UNCHECKED_CAST")
                val doc = context.getBean("doc") as Store<String, String>
                context.getBean("declared")
                requireNotNull(runtime.admin("doc")).readOnly.assert().isTrue()
                requireNotNull(runtime.admin("declared")).readOnly.assert().isFalse()
                doc["k"].assert().isNull()
                val refused = runCatching { doc.put("k", "v") }.exceptionOrNull()
                refused.assert().isInstanceOf(UnsupportedOperationException::class.java)

                // 端点：只读的缓存不接受写操作（400），清空本地副本照常
                val endpoint = CoCacheEndpoint(runtime, context.getBean(CoCacheProperties::class.java))
                runCatching { endpoint.invalidate("doc", arrayOf("k")) }.exceptionOrNull()
                    .assert().isInstanceOf(InvalidEndpointRequestException::class.java)
                    .hasMessageContaining("Cache[doc] is read-only on this instance")
                runCatching { endpoint.broadcast("doc", arrayOf("k")) }.exceptionOrNull()
                    .assert().isInstanceOf(InvalidEndpointRequestException::class.java)
                endpoint.clearLocal("doc").assert().isTrue()
            }
    }

    @Test
    fun readOnlyOnADerivedTypeFailsStartup() {
        runner.withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues("cocache.caches.user.read-only=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause()
                    .hasMessageContaining("cocache.caches.[user].read-only doesn't apply to a Cache")
            }
        // caches-validation=warn：告警，缓存照常是读写的
        runner.withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues("cocache.caches.user.read-only=true", "cocache.caches-validation=warn")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                requireNotNull(context.getBean(CacheRuntime::class.java).admin("user")).readOnly.assert().isFalse()
            }
    }

    @Test
    fun readOnlyInGlobalLayersIsSkipped() {
        runner.withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues("cocache.defaults.store.read-only=true", "cocache.overrides.store.read-only=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("doc")
                val admin = requireNotNull(context.getBean(CacheRuntime::class.java).admin("doc"))
                admin.readOnly.assert().isFalse()
                requireNotNull(admin.policyReport).skipped.map { it.key }.assert()
                    .contains("cocache.defaults.store.read-only", "cocache.overrides.store.read-only")
            }
    }

    @Test
    fun healthOfANonRedisBackendIsUnchanged() {
        runner.withUserConfiguration(SpecConfig::class.java).run { context ->
            val health = CoCacheHealthIndicator(context.getBean(CacheRuntime::class.java)).health()
            // 内存后端不是 Redis：照常报告它的原子广播能力
            health.details["atomicBroadcast"].assert().isEqualTo(true)
        }
    }
}
