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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.redis.RedisBackend
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * 接口式声明（`@CoCache` 接口，经 starter 装配）的命中路径基准：`l2HitKeyExpression` 的 key 按 `keyExpression` 编码，每次调用求值一次
 * 编译模式的 SpEL 模板（RFC 0005 §4.9）；`l2HitAnnotated` 是同一容器中按 `toString()` 编码的对照。
 *
 * 需要 localhost:6379 的 Redis：`./gradlew :cocache-spring-boot-starter:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2HitKeyExpression`；
 * 对比用 `scripts/jmh-compare.sh -m :cocache-spring-boot-starter -b l2HitAnnotated,l2HitKeyExpression -t 1,8`。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class AnnotatedCacheBenchmark {
    private val hotKey = OrderKey("acme", 42)
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var plain: AnnotatedBenchCache
    private lateinit var keyed: KeyExpressionBenchCache

    @Setup
    fun setup() {
        context = AnnotationConfigApplicationContext(BenchConfig::class.java)
        plain = context.getBean(AnnotatedBenchCache::class.java)
        keyed = context.getBean(KeyExpressionBenchCache::class.java)
        plain.put("hot", "hot-value")
        keyed.put(hotKey, "hot-value")
        // 预热越过 SpEL 的解释执行阶段：测量的是编译之后的模板
        repeat(WARM_UP) {
            plain["hot"]
            keyed[hotKey]
        }
    }

    @TearDown
    fun tearDown() {
        context.close()
    }

    /** L2 命中，经接口代理，key 按 `toString()` 编码。 */
    @Benchmark
    fun l2HitAnnotated(): String? = plain["hot"]

    /** L2 命中，经接口代理，key 按 `keyExpression` 编码。 */
    @Benchmark
    fun l2HitKeyExpression(): String? = keyed[hotKey]

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(CoCacheAutoConfiguration::class)
    @EnableCoCache(caches = [AnnotatedBenchCache::class, KeyExpressionBenchCache::class])
    open class BenchConfig {
        @Bean(destroyMethod = "close")
        open fun backend(): CoherenceBackend = RedisBackend.standalone(RedisURI.create("localhost", 6379))

        @Bean
        open fun plainLoader(): CacheLoader<String, String> = CacheLoader { "value-$it" }

        @Bean
        open fun orderLoader(): CacheLoader<OrderKey, String> = CacheLoader { "order-${it.tenant}-${it.id}" }

        @Bean
        open fun stringCodec(): Codec<String> = StringCodec
    }

    private companion object {
        const val WARM_UP = 1_000
    }
}

/**
 * `keyExpression` 求值的根对象。
 */
data class OrderKey(val tenant: String, val id: Long)

@CoCache(ttl = 600)
interface AnnotatedBenchCache : Cache<String, String>

@CoCache(ttl = 600, keyExpression = "#{#root.tenant}:#{#root.id}")
interface KeyExpressionBenchCache : Cache<OrderKey, String>
