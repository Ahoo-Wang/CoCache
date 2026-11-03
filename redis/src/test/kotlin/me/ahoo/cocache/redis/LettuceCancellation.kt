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

import io.lettuce.core.ConnectionEvents
import io.lettuce.core.pubsub.PubSubCommandHandler
import io.lettuce.core.resource.NettyCustomizer
import io.netty.channel.Channel
import me.ahoo.cocache.test.CommandCancellation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 记录客户端资源建立的 Netty 通道（命令连接、订阅连接及其重连），供 [LettuceCancellation] 重置命令连接。
 */
internal class LettuceChannels : NettyCustomizer {
    private val channels = ConcurrentHashMap.newKeySet<Channel>()

    override fun afterChannelInitialized(channel: Channel) {
        channels.add(channel)
        channel.closeFuture().addListener { channels.remove(channel) }
    }

    /**
     * 活动的命令连接：不含订阅连接（重置它会取消在途的心跳 `PING`，订阅者随即被重置，与被测的故障无关）。
     */
    fun commandChannels(): List<Channel> =
        channels.filter { it.isActive && it.pipeline().get(PubSubCommandHandler::class.java) == null }
}

/**
 * 经 [TcpProxy] 连接的后端上“底层命令被 Lettuce 取消”的故障（`FaultSpec.commandCancellation`，RFC 0005 §4.3）：
 * - [hold]：代理丢弃 Redis 发回的数据（[TcpProxy.dropReplies]），命令照常到达并执行，应答不到达客户端，命令停在 Lettuce 的命令栈中；
 * - [cancel]：经 Netty 管道向每个命令连接的 `CommandHandler` 发出 `ConnectionEvents.Reset`，它重置协议状态并以 `cancel()`
 *   结束栈中在途的命令（通道注销时 Lettuce 走同一条路径）；
 * - [close]：断开全部连接并停止丢弃，允许重连。重置之后连接上仍可能有应答丢失的命令，命令栈与应答已经对不上，必须断开重连。
 *
 * 被取消的命令已在 Redis 上执行：读取取得的 lease 由存储按 K14 释放（断开期间被拒绝的释放按退避重发，重连之后到达）。
 */
internal class LettuceCancellation(
    private val proxy: TcpProxy,
    private val channels: LettuceChannels,
) : CommandCancellation {
    private var dropping: AutoCloseable? = null

    override fun hold() {
        dropping = proxy.dropReplies()
    }

    override fun cancel() {
        channels.commandChannels().forEach { channel ->
            channel.eventLoop()
                .submit(Runnable { channel.pipeline().fireUserEventTriggered(ConnectionEvents.Reset()) })
                .get(RESET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        val cut = proxy.cut()
        dropping?.close()
        cut.close()
    }

    private companion object {
        const val RESET_TIMEOUT_SECONDS = 10L
    }
}
