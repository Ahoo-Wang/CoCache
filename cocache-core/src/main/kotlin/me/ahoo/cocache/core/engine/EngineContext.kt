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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * 运行时内所有引擎共享的资源。
 */
internal class EngineContext(
    val nodeId: NodeId,
    val clock: CacheClock,
    val backend: CoherenceBackend,
    val refreshExecutor: Executor,
    val retrier: InvalidationRetrier,
) {
    private val sequence = AtomicLong()

    /**
     * 新令牌：`NodeId + 进程内单调序号`，全局唯一。
     */
    fun nextToken(): Token = Token(nodeId, sequence.incrementAndGet())
}
