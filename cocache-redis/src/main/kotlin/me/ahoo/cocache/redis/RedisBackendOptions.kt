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

package me.ahoo.cocache.redis

import io.lettuce.core.resource.ClientResources
import java.time.Duration

/**
 * Redis 后端选项。
 *
 * @param commandTimeout 单条命令（脚本）的等待上限
 * @param heartbeat 订阅连接的心跳周期：PING 失败或超时即重置所有订阅者（发现半开连接，见 RFC 0003 §4.7）
 * @param invalidateBatchSize 单个失效脚本处理的 key 数上限，避免长时间阻塞分片
 * @param clientResources 后端自建客户端时复用的 Lettuce 资源（事件循环等）；`null` 时自建并随后端关闭
 * @param clientName 后端自建客户端的连接名（`CLIENT SETNAME`），便于在 `CLIENT LIST` 中识别
 * @param channelMode 失效通道模式（RFC 0003 §4.7），全集群必须一致：`PUBLISH` 与 `SPUBLISH` 互不相通
 */
data class RedisBackendOptions(
    val commandTimeout: Duration = Duration.ofSeconds(2),
    val heartbeat: Duration = Duration.ofSeconds(5),
    val invalidateBatchSize: Int = 512,
    val clientResources: ClientResources? = null,
    val clientName: String? = null,
    val channelMode: RedisChannelMode = RedisChannelMode.PUBSUB,
) {
    init {
        require(
            !commandTimeout.isNegative && !commandTimeout.isZero
        ) { "commandTimeout[$commandTimeout] must be positive." }
        require(!heartbeat.isNegative && !heartbeat.isZero) { "heartbeat[$heartbeat] must be positive." }
        require(invalidateBatchSize > 0) { "invalidateBatchSize[$invalidateBatchSize] must be positive." }
    }
}

/**
 * 失效通道模式（RFC 0003 §4.7）。
 */
enum class RedisChannelMode {
    /**
     * 经典 Pub/Sub（默认）：失效与广播在同一脚本内完成（原子）；Cluster 中 `PUBLISH` 经集群总线发往全部节点。
     */
    PUBSUB,

    /**
     * Sharded Pub/Sub（Redis 7+）：`SSUBSCRIBE` / `SPUBLISH`，消息只在频道所属分片内传播。
     *
     * 脚本内的 `SPUBLISH` 要求频道与 key 同 slot，因此以非原子模式运行（`atomicBroadcast = false`）：
     * 写入成功后由引擎单独发布（RFC 0003 §8 Q2 的默认选择）。
     */
    SHARDED,
}
