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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * [TcpProxy] 断线期间不释放监听端口：否则恢复时在同一端口重新监听，可能因端口已被别的连接取作临时端口而失败。
 */
class TcpProxyTest {
    /** 回显服务，作为被转发的目标。 */
    private val echo = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { server ->
        thread(isDaemon = true, name = "tcp-proxy-test-echo") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                thread(isDaemon = true) { client.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
    }
    private val proxy = TcpProxy(echo.inetAddress.hostAddress, echo.localPort)

    @AfterEach
    fun destroy() {
        proxy.close()
        echo.close()
    }

    private fun roundTrip(): Int = Socket(proxy.address, proxy.port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(42)
        socket.getInputStream().read()
    }

    @Test
    fun cutKeepsThePortAndRefusesUntilRestored() {
        roundTrip().assert().isEqualTo(42)
        val established = Socket(proxy.address, proxy.port).apply { soTimeout = 5_000 }
        established.use {
            it.getOutputStream().write(1)
            it.getInputStream().read().assert().isEqualTo(1)
            proxy.cut().use { _ ->
                // 已转发的连接被断开
                runCatching { it.getInputStream().read() }.getOrElse { -1 }.assert().isEqualTo(-1)
                // 端口仍被占用：别的套接字无法取得它
                runCatching {
                    val taken = InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port)
                    Socket().use { other -> other.bind(taken) }
                }.exceptionOrNull().assert().isInstanceOf(IOException::class.java)
                // 新连接被接受后立即关闭，不转发
                runCatching { roundTrip() }.getOrElse { -1 }.assert().isEqualTo(-1)
            }
        }
        roundTrip().assert().isEqualTo(42)
    }
}
