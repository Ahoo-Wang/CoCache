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
import me.ahoo.cocache.redis.StoreEvictionRisk
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/**
 * CoCache 健康检查（`/actuator/health/cocache`）。
 *
 * 状态：只有失效通道心跳失败时为 `DOWN`（此时其它节点的失效可能丢失，本节点 L2 已被重置，L1 仍可用）；其余情况为 `UP`。
 * 失效重试中的 key 不影响状态（重试队列会自行收敛，放弃时计入 `cocache.invalidations.dropped`），只在详情中列出。
 * 命令连接的心跳同样只在详情中列出、不影响状态：大载荷的队头阻塞会让一次 `PING` 超时，命令连接不可用时读取降级回源、写入失败，
 * 让实例被判为不健康（重启）也不会让 Redis 恢复。
 *
 * 详情：后端类型、线格式版本、`atomicBroadcast`；Redis 后端另有通道模式、订阅连接的心跳（`heartbeat`）与命令连接的心跳
 * （`commandConnection`）；每个缓存的失效重试数与本地副本数。
 * 有驱逐检查（[StoreEvictionCheck]）时另有最近一次的结论（`storeEviction`），同样只列出、不影响状态。
 * 只读取内存中的状态，不访问 Redis。
 */
class CoCacheHealthIndicator @JvmOverloads constructor(
    private val runtime: CacheRuntime,
    private val storeEviction: () -> StoreEvictionCheck? = { null },
) : AbstractHealthIndicator(
    "CoCache health check failed"
) {

    override fun doHealthCheck(builder: Health.Builder) {
        val backend = runtime.backend
        builder.withDetail("backend", backend.javaClass.simpleName)
            .withDetail("format", backend.format)
            .withDetail("atomicBroadcast", backend.atomicBroadcast)
        var healthy = true
        // 经 unwrap 看穿包装（如 HookedBackend）：包装过的 Redis 后端同样报告心跳
        backend.unwrap(RedisBackend::class.java)?.let { redis ->
            healthy = redis.heartbeatHealthy
            builder.withDetail("channelMode", redis.channelMode.name.lowercase())
            builder.withDetail("heartbeat", heartbeatOf(redis.heartbeatFailure))
            builder.withDetail("commandConnection", heartbeatOf(redis.commandConnectionFailure))
        }
        storeEvictionOf(storeEviction())?.let { builder.withDetail("storeEviction", it) }
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

    /**
     * 驱逐检查此刻的结论（[StoreEvictionCheck.current]，不访问 Redis）；没有检查、尚未检查或不适用（后端不是 Redis、没有权威缓存）时为
     * `null`。
     */
    private fun storeEvictionOf(check: StoreEvictionCheck?): Map<String, Any>? {
        val verdict = check?.current()
        val checkedAt = check?.checkedAt?.toString() ?: return null
        return when (verdict) {
            is StoreEvictionRisk.Safe -> mapOf("safe" to true, "policies" to verdict.policies, "checkedAt" to checkedAt)
            is StoreEvictionRisk.Risk -> mapOf(
                "safe" to false,
                "risk" to verdict.kind.name.lowercase().replace('_', '-'),
                "caches" to verdict.caches,
                "policies" to verdict.policies,
                "checkedAt" to checkedAt,
            )
            else -> null
        }
    }

    private fun heartbeatOf(failure: String?): Map<String, Any> {
        failure ?: return mapOf("healthy" to true)
        return mapOf("healthy" to false, "error" to failure)
    }
}
