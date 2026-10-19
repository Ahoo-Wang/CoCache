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

import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.redis.RedisBackendOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * CoCache 6.0 配置（`cocache.next.*`），与 5.x 并存期间默认关闭（RFC 0003 §9）。
 *
 * 策略来源的优先级（后者覆盖前者）：内置默认值 → 声明（spec）→ [me.ahoo.cocache.spring.boot.CacheSpecCustomizer]
 * → `cocache.next.defaults.*` → `cocache.next.caches.[name].*`。
 */
@ConfigurationProperties(prefix = CoCacheNextProperties.PREFIX)
data class CoCacheNextProperties(
    val enabled: Boolean = false,
    val defaults: PolicyProperties = PolicyProperties(),
    /**
     * 按缓存名覆盖策略；出现未声明的缓存名时启动失败。
     */
    val caches: Map<String, PolicyProperties> = emptyMap(),
    val redis: Redis = Redis(),
) {
    /**
     * 按缓存名合并配置文件中的策略。
     */
    fun policyFor(cacheName: String, declared: CachePolicy): CachePolicy {
        val withDefaults = defaults.applyTo(declared)
        return caches[cacheName]?.applyTo(withDefaults) ?: withDefaults
    }

    data class Redis(
        val commandTimeout: Duration = Duration.ofSeconds(2),
        val heartbeat: Duration = Duration.ofSeconds(5),
        val invalidateBatchSize: Int = 512,
        val clientName: String? = null,
    ) {
        fun toOptions(): RedisBackendOptions = RedisBackendOptions(
            commandTimeout,
            heartbeat,
            invalidateBatchSize,
            clientName = clientName
        )
    }

    /**
     * 策略的可覆盖字段：未设置（`null`）的字段沿用下层的值。
     */
    data class PolicyProperties(
        val ttl: Duration? = null,
        /**
         * 为 `true` 时命中值永不过期（覆盖 [ttl]）。
         */
        val ttlForever: Boolean? = null,
        val ttlJitter: Duration? = null,
        val absentTtl: Duration? = null,
        val localMaxAge: Duration? = null,
        val degradedLocalMaxAge: Duration? = null,
        val refreshWindow: Duration? = null,
        val leaseTtl: Duration? = null,
        val leaseWait: Duration? = null,
        val loadMaxConcurrent: Int? = null,
        val loadAcquireTimeout: Duration? = null,
        val localMaximumSize: Long? = null,
        val invalidateFailure: InvalidateFailureMode? = null,
        /**
         * `throw` 时 L1 读失败直接抛出（对应 5.x 的 `cocache.redis.strict-failure=true`），默认降级回源。
         */
        val readFailure: ReadFailureMode? = null,
    ) {
        fun applyTo(policy: CachePolicy): CachePolicy {
            val builder = policy.toBuilder()
            applyLifetimes(builder)
            applyLease(builder, policy)
            applyLoad(builder, policy)
            localMaximumSize?.let { builder.local = policy.local.copy(maximumSize = it) }
            invalidateFailure?.let { builder.invalidateFailure = it }
            readFailure?.let { builder.readFailure = it }
            return builder.build()
        }

        private fun applyLifetimes(builder: CachePolicy.Builder) {
            when {
                ttlForever == true -> builder.ttl = null
                ttl != null -> builder.ttl = ttl
            }
            ttlJitter?.let { builder.ttlJitter = it }
            absentTtl?.let { builder.absentTtl = it }
            localMaxAge?.let { builder.localMaxAge = it }
            degradedLocalMaxAge?.let { builder.degradedLocalMaxAge = it }
            refreshWindow?.let { builder.refreshWindow = it }
        }

        private fun applyLease(builder: CachePolicy.Builder, policy: CachePolicy) {
            if (leaseTtl != null || leaseWait != null) {
                builder.lease = LeasePolicy(leaseTtl ?: policy.lease.ttl, leaseWait ?: policy.lease.wait)
            }
        }

        private fun applyLoad(builder: CachePolicy.Builder, policy: CachePolicy) {
            if (loadMaxConcurrent != null || loadAcquireTimeout != null) {
                builder.load = LoadPolicy(loadMaxConcurrent ?: policy.load.maxConcurrent, loadAcquireTimeout ?: policy.load.acquireTimeout)
            }
        }
    }

    companion object {
        const val PREFIX = "cocache.next"
    }
}
