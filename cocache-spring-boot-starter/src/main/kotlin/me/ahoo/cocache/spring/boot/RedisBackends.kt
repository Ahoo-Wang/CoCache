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

import io.lettuce.core.RedisURI
import io.lettuce.core.SslOptions
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisBackendOptions
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.boot.ssl.SslBundle
import java.net.URI

/**
 * 由 Spring Boot 的 Redis 连接信息构造后端。后端自建 Lettuce 客户端（断线时拒绝命令、只访问主节点），
 * 不复用应用的连接工厂：客户端选项是全局的，不能为 CoCache 改动应用的连接行为。
 */
object RedisBackends {
    /**
     * 连接目标：单机（含 Sentinel URI）或集群。
     */
    sealed interface Target {
        data class Standalone(val uri: RedisURI) : Target

        data class Cluster(val uris: List<RedisURI>) : Target
    }

    /**
     * @param urlUsesSsl `spring.data.redis.url` 使用 `rediss://`：没有 SSL bundle 时也启用 TLS（JDK 默认信任库）
     */
    @JvmOverloads
    fun create(
        details: DataRedisConnectionDetails,
        options: RedisBackendOptions,
        urlUsesSsl: Boolean = false
    ): RedisBackend {
        val withSsl = details.sslBundle?.let { options.copy(sslOptions = sslOptionsOf(it)) } ?: options
        return when (val target = resolve(details, urlUsesSsl)) {
            is Target.Cluster -> RedisBackend.cluster(target.uris, withSsl)
            is Target.Standalone -> RedisBackend.standalone(target.uri, withSsl)
        }
    }

    /**
     * `spring.data.redis.url` 是否使用 `rediss://`。
     */
    fun urlUsesSsl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }
        return URI.create(url.trim()).scheme.equals("rediss", ignoreCase = true)
    }

    /**
     * Spring Boot 的 SSL bundle → Lettuce 的 TLS 选项：密钥与信任管理器、启用的协议与加密套件。
     */
    fun sslOptionsOf(bundle: SslBundle): SslOptions {
        val builder = SslOptions.builder()
            .jdkSslProvider()
            .keyManager(bundle.managers.keyManagerFactory)
            .trustManager(bundle.managers.trustManagerFactory)
        bundle.options.enabledProtocols?.takeIf { it.isNotEmpty() }?.let { builder.protocols(*it) }
        bundle.options.ciphers?.takeIf { it.isNotEmpty() }?.let { builder.cipherSuites(*it) }
        return builder.build()
    }

    /**
     * 解析连接目标：集群优先，其次 Sentinel，最后单机。主从（master-replica）不支持，启动失败。
     *
     * @param urlUsesSsl `spring.data.redis.url` 使用 `rediss://`（见 [urlUsesSsl]）
     */
    @JvmOverloads
    fun resolve(details: DataRedisConnectionDetails, urlUsesSsl: Boolean = false): Target {
        val ssl = details.sslBundle != null || urlUsesSsl
        details.cluster?.let { cluster ->
            return Target.Cluster(
                cluster.nodes.map { node ->
                    auth(
                        RedisURI.Builder.redis(node.host, node.port),
                        details.username,
                        details.password
                    ).withSsl(ssl).build()
                }
            )
        }
        details.sentinel?.let { sentinel ->
            val builder = RedisURI.builder()
                .withSentinelMasterId(sentinel.master)
                .withDatabase(sentinel.database)
                .withSsl(ssl)
            // Sentinel 节点用自己的凭据（spring.data.redis.sentinel.username/password），数据节点用 spring.data.redis.username/password
            sentinel.nodes.forEach { node ->
                builder.withSentinel(
                    auth(
                        RedisURI.Builder.redis(node.host, node.port),
                        sentinel.username,
                        sentinel.password
                    ).withSsl(ssl).build()
                )
            }
            return Target.Standalone(auth(builder, details.username, details.password).build())
        }
        // 静态主从（spring.data.redis.masterreplica.nodes）：CoCache 的脚本与订阅都需要主节点，按单机连接会悄悄连到 localhost
        check(details.masterReplica == null) {
            "CoCache does not support Redis master-replica (spring.data.redis.masterreplica.*); " +
                "use standalone, Sentinel or Cluster, or declare a CoherenceBackend bean."
        }
        val standalone = requireNotNull(
            details.standalone
        ) { "Redis connection details define neither cluster, sentinel nor standalone." }
        val builder = RedisURI.Builder.redis(
            standalone.host,
            standalone.port
        ).withDatabase(standalone.database).withSsl(ssl)
        return Target.Standalone(auth(builder, details.username, details.password).build())
    }

    private fun auth(builder: RedisURI.Builder, username: String?, password: String?): RedisURI.Builder {
        if (password.isNullOrEmpty()) {
            return builder
        }
        return if (username.isNullOrEmpty()) {
            builder.withPassword(
                password.toCharArray()
            )
        } else {
            builder.withAuthentication(username, password)
        }
    }
}
