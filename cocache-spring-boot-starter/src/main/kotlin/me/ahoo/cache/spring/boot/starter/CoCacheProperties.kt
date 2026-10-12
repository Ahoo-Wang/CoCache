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

import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.consistency.SourceLoadLimit
import me.ahoo.cache.spring.redis.codec.AbstractCodecExecutor
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 失效通道类型，见 [CoCacheProperties.Redis.evictionChannel]。
 */
enum class EvictionChannel {
    PUBSUB,
    SHARDED
}

/**
 * CoCache Properties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = CoCache.COCACHE)
data class CoCacheProperties(
    val enabled: Boolean = true,
    val redis: Redis = Redis(),
    val metrics: Metrics = Metrics(),
    val load: Load = Load(),
) {
    data class Load(
        /**
         * 每个缓存同时进行的回源数上限（bulkhead）；`0` 表示不限制。Redis 故障时每个 L2 未命中都会回源，此限制保护数据源。
         */
        val maxConcurrent: Int = 0,
        /**
         * 达到上限时等待许可的最长时间，超时抛出 `SourceLoadRejectedException`。
         */
        val acquireTimeout: Duration = SourceLoadLimit.DEFAULT_ACQUIRE_TIMEOUT,
    ) {
        fun toSourceLoadLimit(): SourceLoadLimit = SourceLoadLimit(maxConcurrent, acquireTimeout)
    }

    data class Metrics(
        /**
         * 统计 L2 命中（`cocache.hits{level=l2}`）。L2 命中是最热的路径，计数会使其吞吐下降约 8%（单线程）
         * 到 15%（8 线程），因此默认关闭。
         */
        val clientSideHits: Boolean = false,
    )

    data class Redis(
        /**
         * Redis 故障时重抛异常（默认 false = 降级：读按未命中、写/evict 仅告警）。
         */
        val strictFailure: Boolean = false,
        /**
         * 自定义 missing-guard 哨兵值。约束见 [AbstractCodecExecutor]：
         * 自定义值与默认值互不识别，需全集群同时切换，且不得与任何合法业务序列化值相等。
         */
        val missingGuardSentinel: String = AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL,
        /**
         * 失效通道：`pubsub`（默认，PUBLISH/SUBSCRIBE）或 `sharded`（SPUBLISH/SSUBSCRIBE，Redis 7+，需要 Lettuce）。
         * 两者互不相通，需全集群同时切换。
         */
        val evictionChannel: EvictionChannel = EvictionChannel.PUBSUB,
    ) {
        init {
            require(missingGuardSentinel.isNotBlank()) { "cocache.redis.missing-guard-sentinel must not be blank." }
        }
    }
}
