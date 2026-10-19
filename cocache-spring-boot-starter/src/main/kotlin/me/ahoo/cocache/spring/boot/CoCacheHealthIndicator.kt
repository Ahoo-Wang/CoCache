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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/**
 * CoCache 健康检查（`/actuator/health/cocache`）。
 *
 * 状态：只有失效通道心跳失败时为 `DOWN`（此时其它节点的失效可能丢失，本节点 L2 已被重置，L1 仍可用）；其余情况为 `UP`。
 * 失效重试中的 key 不影响状态（重试队列会自行收敛，放弃时计入 `cocache.invalidations.dropped`），只在详情中列出。
 *
 * 详情：后端类型、线格式版本、`atomicBroadcast`；Redis 后端另有通道模式与心跳状态；每个缓存的失效重试数与本地副本数。
 * 只读取内存中的状态，不访问 Redis。
 */
class CoCacheHealthIndicator(private val runtime: CacheRuntime) : AbstractHealthIndicator(
    "CoCache health check failed"
) {

    override fun doHealthCheck(builder: Health.Builder) {
        val backend = runtime.backend
        builder.withDetail("backend", backend.javaClass.simpleName)
            .withDetail("format", backend.format)
            .withDetail("atomicBroadcast", backend.atomicBroadcast)
        var healthy = true
        if (backend is RedisBackend) {
            healthy = backend.heartbeatHealthy
            builder.withDetail("channelMode", backend.channelMode.name.lowercase())
            builder.withDetail("heartbeat", heartbeatOf(backend))
        }
        val admins = runtime.cacheNames.sorted().mapNotNull { runtime.admin(it) }
        builder.withDetail("pendingInvalidations", admins.sumOf { it.pendingInvalidationCount })
        builder.withDetail(
            "caches",
            admins.associate { it.name to CacheHealth(it.pendingInvalidationCount, it.localSize) }
        )
        if (healthy) builder.up() else builder.down()
    }

    /**
     * 单个缓存的详情。
     */
    data class CacheHealth(val pendingInvalidations: Int, val localSize: Long)

    private fun heartbeatOf(backend: RedisBackend): Map<String, Any> {
        val failure = backend.heartbeatFailure ?: return mapOf("healthy" to true)
        return mapOf("healthy" to false, "error" to failure)
    }
}
