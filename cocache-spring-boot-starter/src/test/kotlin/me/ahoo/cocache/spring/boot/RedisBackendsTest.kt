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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails.Node
import org.springframework.boot.ssl.SslBundle

class RedisBackendsTest {
    private class Details(
        private val user: String? = null,
        private val secret: String? = null,
        private val ssl: SslBundle? = null,
        private val standaloneTarget: DataRedisConnectionDetails.Standalone? = DataRedisConnectionDetails.Standalone.of(
            "localhost",
            6379,
            2
        ),
        private val clusterTarget: DataRedisConnectionDetails.Cluster? = null,
        private val sentinelTarget: DataRedisConnectionDetails.Sentinel? = null,
        private val masterReplicaTarget: DataRedisConnectionDetails.MasterReplica? = null,
    ) : DataRedisConnectionDetails {
        override fun getUsername(): String? = user
        override fun getPassword(): String? = secret
        override fun getSslBundle(): SslBundle? = ssl
        override fun getStandalone(): DataRedisConnectionDetails.Standalone? = standaloneTarget
        override fun getCluster(): DataRedisConnectionDetails.Cluster? = clusterTarget
        override fun getSentinel(): DataRedisConnectionDetails.Sentinel? = sentinelTarget
        override fun getMasterReplica(): DataRedisConnectionDetails.MasterReplica? = masterReplicaTarget
    }

    private fun standalone(details: DataRedisConnectionDetails) = RedisBackends.resolve(
        details
    ) as RedisBackends.Target.Standalone

    @Test
    fun standaloneWithoutCredentials() {
        val uri = standalone(Details()).uri
        uri.host.assert().isEqualTo("localhost")
        uri.port.assert().isEqualTo(6379)
        uri.database.assert().isEqualTo(2)
        uri.isSsl.assert().isFalse()
        uri.credentialsProvider.resolveCredentials().block()?.hasPassword().assert().isFalse()
    }

    @Test
    fun standaloneWithPasswordOnlyOrUserAndPassword() {
        val passwordOnly = standalone(Details(secret = "pw")).uri.credentialsProvider.resolveCredentials().block()
        String(requireNotNull(passwordOnly).password).assert().isEqualTo("pw")
        passwordOnly.hasUsername().assert().isFalse()

        val full = standalone(Details(user = "u", secret = "pw", ssl = mockk())).uri
        full.isSsl.assert().isTrue()
        requireNotNull(full.credentialsProvider.resolveCredentials().block()).username.assert().isEqualTo("u")

        standalone(
            Details(user = "u", secret = "")
        ).uri.credentialsProvider.resolveCredentials().block()?.hasPassword().assert().isFalse()
    }

    @Test
    fun clusterTakesPrecedence() {
        val cluster = DataRedisConnectionDetails.Cluster { listOf(Node("10.0.0.1", 7000), Node("10.0.0.2", 7001)) }
        val target = RedisBackends.resolve(
            Details(secret = "pw", clusterTarget = cluster)
        ) as RedisBackends.Target.Cluster
        target.uris.map { "${it.host}:${it.port}" }.assert().containsExactly("10.0.0.1:7000", "10.0.0.2:7001")
        target.uris.all { it.credentialsProvider.resolveCredentials().block()?.hasPassword() == true }.assert().isTrue()
    }

    @Test
    fun sentinelBuildsSentinelUri() {
        val sentinel = object : DataRedisConnectionDetails.Sentinel {
            override fun getDatabase(): Int = 3
            override fun getMaster(): String = "mymaster"
            override fun getNodes(): List<Node> = listOf(Node("s1", 26379), Node("s2", 26380))
            override fun getUsername(): String? = null
            override fun getPassword(): String? = null
        }
        val uri = standalone(Details(sentinelTarget = sentinel)).uri
        uri.sentinelMasterId.assert().isEqualTo("mymaster")
        uri.database.assert().isEqualTo(3)
        uri.sentinels.map { "${it.host}:${it.port}" }.assert().containsExactly("s1:26379", "s2:26380")
    }

    @Test
    fun missingTargetFails() {
        runCatching {
            RedisBackends.resolve(
                Details(standaloneTarget = null)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun sslBundleMapsToLettuceSslOptions() {
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        ).apply {
            init(null as java.security.KeyStore?)
        }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        ).apply {
            init(null, null)
        }
        val managers = org.springframework.boot.ssl.SslManagerBundle.of(keys, trust)
        val withOptions = SslBundle.of(
            null,
            null,
            org.springframework.boot.ssl.SslOptions.of(arrayOf("TLS_AES_128_GCM_SHA256"), arrayOf("TLSv1.3")),
            null,
            managers
        )
        val options = RedisBackends.sslOptionsOf(withOptions)
        options.protocols.assert().containsExactly("TLSv1.3")
        options.cipherSuites.assert().containsExactly("TLS_AES_128_GCM_SHA256")

        val defaults = RedisBackends.sslOptionsOf(
            SslBundle.of(null, null, org.springframework.boot.ssl.SslOptions.NONE, null, managers)
        )
        // 未设置时 Lettuce 返回 null（使用 JDK 默认值）
        (defaults.protocols ?: emptyArray()).assert().isEmpty()
        (defaults.cipherSuites ?: emptyArray()).assert().isEmpty()
    }

    @Test
    fun createWithSslBundleBuildsTheBackend() {
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(null as java.security.KeyStore?) }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        ).apply { init(null, null) }
        val bundle = SslBundle.of(
            null,
            null,
            org.springframework.boot.ssl.SslOptions.NONE,
            null,
            org.springframework.boot.ssl.SslManagerBundle.of(keys, trust)
        )
        // 没有服务监听的端口：创建时连接立即失败，验证带 SSL bundle 的创建路径（TLS 选项的映射见上一个用例）
        val details = Details(ssl = bundle, standaloneTarget = DataRedisConnectionDetails.Standalone.of("localhost", 1))
        runCatching {
            RedisBackends.create(
                details,
                me.ahoo.cocache.redis.RedisBackendOptions()
            ).close()
        }.exceptionOrNull().assert().isNotNull()
    }

    @Test
    fun sentinelCredentialsApplyToSentinelNodes() {
        val sentinel = object : DataRedisConnectionDetails.Sentinel {
            override fun getDatabase(): Int = 0
            override fun getMaster(): String = "mymaster"
            override fun getNodes(): List<Node> = listOf(Node("s1", 26379), Node("s2", 26380))
            override fun getUsername(): String = "sentinel-user"
            override fun getPassword(): String = "sentinel-pw"
        }
        val uri = standalone(Details(user = "data-user", secret = "data-pw", sentinelTarget = sentinel)).uri
        uri.sentinels.forEach { node ->
            val credentials = requireNotNull(node.credentialsProvider.resolveCredentials().block())
            credentials.username.assert().isEqualTo("sentinel-user")
            String(credentials.password).assert().isEqualTo("sentinel-pw")
        }
        val data = requireNotNull(uri.credentialsProvider.resolveCredentials().block())
        data.username.assert().isEqualTo("data-user")
        String(data.password).assert().isEqualTo("data-pw")

        // 没有 Sentinel 凭据时 Sentinel 节点不带认证
        val open = object : DataRedisConnectionDetails.Sentinel by sentinel {
            override fun getUsername(): String? = null
            override fun getPassword(): String? = null
        }
        standalone(Details(sentinelTarget = open)).uri.sentinels.all {
            it.credentialsProvider.resolveCredentials().block()?.hasPassword() != true
        }.assert().isTrue()
    }

    @Test
    fun masterReplicaFailsLoudly() {
        val masterReplica = DataRedisConnectionDetails.MasterReplica { listOf(Node("10.0.0.1", 6379)) }
        runCatching {
            RedisBackends.resolve(Details(masterReplicaTarget = masterReplica))
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("master-replica")
    }

    @Test
    fun redissUrlEnablesTls() {
        RedisBackends.urlUsesSsl("rediss://user:pw@redis.example.com:6380/1").assert().isTrue()
        RedisBackends.urlUsesSsl("REDISS://redis.example.com").assert().isTrue()
        RedisBackends.urlUsesSsl("redis://localhost:6379").assert().isFalse()
        RedisBackends.urlUsesSsl(null).assert().isFalse()
        RedisBackends.urlUsesSsl(" ").assert().isFalse()
        standalone(Details()).uri.isSsl.assert().isFalse()
        (
            RedisBackends.resolve(
                Details(),
                urlUsesSsl = true
            ) as RedisBackends.Target.Standalone
            ).uri.isSsl.assert().isTrue()
        val cluster = DataRedisConnectionDetails.Cluster { listOf(Node("10.0.0.1", 7000)) }
        (RedisBackends.resolve(Details(clusterTarget = cluster), urlUsesSsl = true) as RedisBackends.Target.Cluster)
            .uris.all { it.isSsl }.assert().isTrue()
    }

    @Test
    fun redissUrlPropertyReachesTheBackend() {
        // spring.data.redis.url=rediss://…（未设置 ssl.enabled）：Spring Boot 的连接信息不带 SSL bundle，CoCache 按 URL 启用 TLS。
        // 假服务端回一个明文 RESP 错误：启用 TLS 的客户端把它当作非法 TLS 记录（NotSslRecordException）
        // 环境变量指向 Redis Cluster 时 cluster 配置优先于 url，本用例不适用
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("SPRING_DATA_REDIS_CLUSTER_NODES") == null)
        java.net.ServerSocket(0).use { server ->
            val finished = java.util.concurrent.CountDownLatch(1)
            val acceptor = Thread {
                runCatching {
                    while (!server.isClosed) {
                        server.accept().use { it.getOutputStream().write("-ERR plain\r\n".toByteArray()) }
                    }
                }
                finished.countDown()
            }.apply {
                isDaemon = true
                start()
            }
            org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration::class.java,
                        CoCacheAutoConfiguration::class.java
                    )
                )
                .withPropertyValues("spring.data.redis.url=rediss://localhost:${server.localPort}")
                .run { context ->
                    org.assertj.core.api.Assertions.assertThat(context).hasFailed()
                    context.startupFailure.assert().hasStackTraceContaining("NotSslRecordException")
                }
            server.close()
            finished.await(5, java.util.concurrent.TimeUnit.SECONDS).assert().isTrue()
            acceptor.join(1000)
        }
    }
}
