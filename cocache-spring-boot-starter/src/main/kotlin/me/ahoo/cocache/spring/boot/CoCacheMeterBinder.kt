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

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.FunctionTimer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.MeterBinder
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import org.springframework.beans.factory.SmartInitializingSingleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 把每个缓存的 [CacheMetrics] 绑定为 Micrometer 指标（`cocache.*`，以 `cache` 标签区分）。
 *
 * 指标读取累计值，不在缓存的读写路径上额外记录。缓存在首次注入时才创建，因此绑定按 [CacheRuntime.cacheNames]
 * 增量进行：[bindTo] 绑定当时已有的缓存并记住 registry，之后创建的缓存由 [bindNewCaches] 补绑
 * （所有单例创建完成时、以及每个缓存 bean 初始化后自动调用）。
 */
class CoCacheMeterBinder(private val runtime: CacheRuntime) : MeterBinder, SmartInitializingSingleton {
    companion object {
        const val TAG_CACHE = "cache"
        const val TAG_LEVEL = "level"
    }

    /**
     * registry → 已绑定的缓存名。
     */
    private val bound = ConcurrentHashMap<MeterRegistry, MutableSet<String>>()

    override fun bindTo(registry: MeterRegistry) {
        bound.computeIfAbsent(registry) { ConcurrentHashMap.newKeySet() }
        bindNewCaches()
    }

    override fun afterSingletonsInstantiated() {
        bindNewCaches()
    }

    /**
     * 为尚未绑定的缓存注册指标；重复调用是安全的。
     */
    fun bindNewCaches() {
        val names = runtime.cacheNames
        bound.forEach { (registry, boundNames) ->
            names.filter { boundNames.add(it) }.forEach { name ->
                runtime.admin(name)?.let { bindCache(registry, it) }
            }
        }
    }

    private fun bindCache(registry: MeterRegistry, admin: CacheAdmin) {
        val tags = Tags.of(TAG_CACHE, admin.name)
        val metrics = admin.metrics
        CacheCounters.ALL.forEach { counter ->
            FunctionCounter.builder(counter.meter, metrics) { counter.read(it).toDouble() }
                .description(counter.description)
                .tags(counter.level?.let { tags.and(TAG_LEVEL, it) } ?: tags)
                .register(registry)
        }
        FunctionTimer.builder(
            "cocache.loads",
            metrics,
            { it.loads },
            { it.loadNanos.toDouble() },
            TimeUnit.NANOSECONDS
        )
            .description("Loader calls")
            .tags(tags)
            .register(registry)
        Gauge.builder("cocache.local.size", admin) { it.localSize.toDouble() }
            .description("Entries in L2 (estimated)")
            .tags(tags)
            .register(registry)
    }
}
