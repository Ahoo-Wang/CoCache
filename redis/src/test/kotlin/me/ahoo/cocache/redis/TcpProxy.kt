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

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * 本机 TCP 转发，用于模拟断线：[cut] 关闭全部已转发的连接，此后新连接一经接受就被关闭（客户端的握手失败、继续重连），
 * 恢复后照常转发。[stall] 模拟建立缓慢的连接：新连接被接受但暂不转发（客户端的握手等待应答），恢复后才开始转发。
 * [cut] 期间客户端的重连握手立即失败，Lettuce 随后让新命令以该连接错误快速失败；[stall] 期间重连停在握手上，断线期间的命令被缓冲。
 * [pause] 模拟只作用于经本代理的连接的 `CLIENT PAUSE`：客户端发出的命令已写出，到期后才到达 Redis。
 *
 * 断线期间监听端口始终保持绑定：若关闭后在同一端口重新监听，端口空闲的间隙可能被本机任一出站连接取作临时端口，
 * 恢复时 `bind` 以 `Address already in use` 失败（CI 上偶发）。
 */
class TcpProxy(private val targetHost: String, private val targetPort: Int) : AutoCloseable {
    private val lock = Any()
    private val sockets = HashSet<Socket>()
    private val stalled = ArrayList<Socket>()
    private val server: ServerSocket = listen()
    private var refusing = false
    private var stalling = false

    /**
     * 暂停的截止时刻（`System.nanoTime()`）：此前客户端发来的数据先读入、暂不转发。
     */
    @Volatile
    private var pausedUntil = System.nanoTime()

    val port: Int = server.localPort

    val address: String = server.inetAddress.hostAddress

    private fun listen(): ServerSocket {
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), BACKLOG)
        thread(isDaemon = true, name = "tcp-proxy-${server.localPort}") { acceptLoop(server) }
        return server
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            synchronized(lock) {
                when {
                    server.isClosed || refusing -> client.close()
                    stalling -> stalled.add(client)
                    else -> connect(client)
                }
            }
        }
    }

    private fun connect(client: Socket) {
        val upstream = try {
            Socket(targetHost, targetPort)
        } catch (_: IOException) {
            client.close()
            return
        }
        sockets.add(client)
        sockets.add(upstream)
        pipe(client, upstream, pausable = true)
        pipe(upstream, client, pausable = false)
    }

    /**
     * [pausable] 的方向（客户端发往 Redis）在 [pause] 期间暂不转发读入的数据，到期后按序转发。
     */
    private fun pipe(from: Socket, to: Socket, pausable: Boolean) = thread(isDaemon = true, name = "tcp-proxy-pipe") {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (pausable) {
                    awaitResume()
                }
                output.write(buffer, 0, read)
            }
        } catch (_: IOException) {
            // 连接被关闭
        } finally {
            from.close()
            to.close()
            synchronized(lock) {
                sockets.remove(from)
                sockets.remove(to)
            }
        }
    }

    private fun disconnectAll() {
        sockets.forEach { it.close() }
        sockets.clear()
    }

    /**
     * 断开全部连接并拒绝重连，关闭返回值即恢复。
     */
    fun cut(): AutoCloseable {
        synchronized(lock) {
            refusing = true
            disconnectAll()
        }
        return AutoCloseable {
            synchronized(lock) { refusing = false }
        }
    }

    /**
     * 新连接被接受但暂不转发（客户端的握手等待应答，不会失败），关闭返回值即开始转发这些连接。
     * [disconnect] 时同时断开全部已转发的连接（客户端随即重连，重连停在握手上），否则已转发的连接不受影响。
     */
    fun stall(disconnect: Boolean = false): AutoCloseable {
        synchronized(lock) {
            stalling = true
            if (disconnect) {
                disconnectAll()
            }
        }
        return AutoCloseable {
            synchronized(lock) {
                stalling = false
                stalled.forEach { connect(it) }
                stalled.clear()
            }
        }
    }

    /**
     * 暂停 [millis] 毫秒：已转发的连接与新连接（握手）上客户端发来的数据先读入、暂不转发，到期后按序转发；应答方向不受影响。
     * 与 `CLIENT PAUSE` 相同，暂停期间再次调用时取较晚的截止时刻。
     *
     * 代替服务端的 `CLIENT PAUSE`：它暂停共用这个 Redis 的所有客户端（并行运行的 starter、example 测试任务，本机其它构建），
     * 服务端按 `hz` 检查暂停是否到期，暂停开始时到达的命令要等得比暂停时长更久，超过它们默认 2 s 的 `commandTimeout`。
     */
    fun pause(millis: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        synchronized(lock) {
            if (until - pausedUntil > 0) {
                pausedUntil = until
            }
        }
    }

    private fun awaitResume() {
        while (true) {
            val remaining = pausedUntil - System.nanoTime()
            if (remaining <= 0) {
                return
            }
            LockSupport.parkNanos(remaining)
        }
    }

    override fun close() {
        synchronized(lock) {
            server.close()
            disconnectAll()
            stalled.forEach { it.close() }
            stalled.clear()
        }
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER_SIZE = 8 * 1024
    }
}
