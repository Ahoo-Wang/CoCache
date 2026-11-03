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

package me.ahoo.cocache.test

/**
 * 底层命令被取消的故障（[FaultSpec.commandCancellation]，RFC 0005 §4.3）：先让后端的 L1 命令停在底层客户端中，再由底层客户端取消它们。
 * 底层客户端取消的命令是结果未知的 L1 故障，不是调用方的取消：存储以其它异常报告（可以把原取消异常作为 cause），从不抛出
 * `CancellationException`，并按 K14 释放这些命令取得的令牌。
 *
 * 用例按序调用 [hold]、[cancel]、[close]，每个至多一次。
 */
interface CommandCancellation : AutoCloseable {
    /**
     * 此后发出的 L1 命令停在底层客户端中，不完成（如应答不到达客户端），直到 [cancel]。命令可以已经执行，也可以没有执行。
     */
    fun hold()

    /**
     * 底层客户端取消停住的命令：`FutureL1Store` 的实现取消它返回的 future；Lettuce 在连接重置时以 `cancel()` 结束在途的命令。
     * 返回时这些命令都已被取消。之后到 [close] 之前发出的命令可以同样被取消、失败，或停到命令超时。
     */
    fun cancel()

    /**
     * 恢复：之后的命令照常执行（可以需要重连，用例轮询等到 L1 可用）。
     */
    override fun close()
}
