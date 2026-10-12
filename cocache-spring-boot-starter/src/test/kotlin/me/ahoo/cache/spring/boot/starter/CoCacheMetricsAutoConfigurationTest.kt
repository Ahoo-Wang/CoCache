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

package me.ahoo.cache.spring.boot.starter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.example.cache.UserCache
import me.ahoo.cache.spring.boot.starter.auto.EnableCoCacheConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class CoCacheMetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            DataRedisAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoCacheMetricsAutoConfiguration::class.java,
            EnableCoCacheConfiguration::class.java
        )

    @Test
    fun bindCacheMetrics() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(CoCacheMeterBinder::class.java)
            val registry = SimpleMeterRegistry()
            context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
            val userCache = context.getBean(UserCache::class.java)
            val cacheName = "UserCache"

            userCache[UUID.randomUUID().toString()]

            registry.get("cocache.misses").tag(CoCacheMeterBinder.TAG_CACHE, cacheName).functionCounter()
                .count().assert().isGreaterThanOrEqualTo(1.0)
            val loads = registry.get("cocache.loads").tag(CoCacheMeterBinder.TAG_CACHE, cacheName).functionTimer()
            loads.count().assert().isGreaterThanOrEqualTo(1.0)
            loads.totalTime(TimeUnit.NANOSECONDS).assert().isGreaterThan(0.0)
            registry.find("cocache.hits").tag(CoCacheMeterBinder.TAG_LEVEL, "l2").meters().assert().isEmpty()
            registry.get("cocache.hits").tag(CoCacheMeterBinder.TAG_CACHE, cacheName)
                .tag(CoCacheMeterBinder.TAG_LEVEL, "l1").functionCounter()
                .count().assert().isGreaterThanOrEqualTo(0.0)
            // 监听容器异步分发订阅回调，onReset 可能在读取之后清空 L2，因此只断言非负
            registry.get("cocache.l2.size").tag(CoCacheMeterBinder.TAG_CACHE, cacheName).gauge()
                .value().assert().isGreaterThanOrEqualTo(0.0)
            listOf(
                "cocache.filtered",
                "cocache.load.failures",
                "cocache.stale.discards",
                "cocache.evictions.received",
                "cocache.resets",
                "cocache.refreshes",
                "cocache.load.rejections"
            ).forEach {
                registry.get(it).tag(CoCacheMeterBinder.TAG_CACHE, cacheName).functionCounter()
                    .count().assert().isGreaterThanOrEqualTo(0.0)
            }
            registry.get("cocache.eviction.publish.failures").functionCounter().count().assert().isZero()
        }
    }

    @Test
    fun bindClientSideHitsWhenEnabled() {
        contextRunner
            .withPropertyValues("cocache.metrics.client-side-hits=true")
            .run { context ->
                val registry = SimpleMeterRegistry()
                context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
                val userCache = context.getBean(UserCache::class.java)
                val key = UUID.randomUUID().toString()

                val l2Hits = registry.get("cocache.hits").tag(CoCacheMeterBinder.TAG_CACHE, "UserCache")
                    .tag(CoCacheMeterBinder.TAG_LEVEL, "l2").functionCounter()

                // 异步的通道重置可能清空 L2，因此轮询直到出现一次 L2 命中
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (l2Hits.count() < 1.0 && System.nanoTime() < deadline) {
                    userCache[key]
                }
                l2Hits.count().assert().isGreaterThanOrEqualTo(1.0)
            }
    }

    @Test
    fun skipPublishFailuresForNonRedisEventBus() {
        contextRunner.run { context ->
            val registry = SimpleMeterRegistry()
            CoCacheMeterBinder(context.getBean(CacheFactory::class.java), NoOpCacheEvictedEventBus).bindTo(registry)
            registry.find("cocache.eviction.publish.failures").meter().assert().isNull()
            registry.find("cocache.misses").meters().assert().isNotEmpty()
        }
    }

    @Test
    fun backOffWithoutMicrometer() {
        contextRunner
            .withClassLoader(FilteredClassLoader("io.micrometer.core"))
            .run { context ->
                assertThat(context).doesNotHaveBean(CoCacheMeterBinder::class.java)
            }
    }
}
