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
import kotlin.concurrent.thread

/**
 * 本机 TCP 转发，用于模拟断线：[cut] 关闭全部已转发的连接，此后新连接一经接受就被关闭（客户端的握手失败、继续重连），
 * 恢复后照常转发。
 *
 * 断线期间监听端口始终保持绑定：若关闭后在同一端口重新监听，端口空闲的间隙可能被本机任一出站连接取作临时端口，
 * 恢复时 `bind` 以 `Address already in use` 失败（CI 上偶发）。
 */
class TcpProxy(private val targetHost: String, private val targetPort: Int) : AutoCloseable {
    private val lock = Any()
    private val sockets = HashSet<Socket>()
    private val server: ServerSocket = listen()
    private var refusing = false

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
                if (server.isClosed || refusing) client.close() else connect(client)
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
        pipe(client, upstream)
        pipe(upstream, client)
    }

    private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true, name = "tcp-proxy-pipe") {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
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

    override fun close() {
        synchronized(lock) {
            server.close()
            disconnectAll()
        }
    }

    private companion object {
        const val BACKLOG = 50
    }
}
