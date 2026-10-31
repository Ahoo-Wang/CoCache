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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisChannelMode
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.endpoint.InvocationContext
import org.springframework.boot.actuate.endpoint.SecurityContext
import org.springframework.boot.actuate.endpoint.invoke.convert.ConversionServiceParameterValueMapper
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 运维：健康检查与端点的按 key 查看、强制失效。
 */
class CoCacheOperationsTest {
    private val autoConfigurations = AutoConfigurations.of(
        CoCacheAutoConfiguration::class.java,
        CoCacheEndpointAutoConfiguration::class.java,
        CoCacheHealthAutoConfiguration::class.java,
    )
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withPropertyValues("management.endpoints.web.exposure.include=cocache")
        .withUserConfiguration(InMemoryConfig::class.java)

    @Configuration(proxyBeanMethods = false)
    class InMemoryConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<Long, String> = cacheSpec("user") {
            codec = StringCodec
            keyEncoder = KeyEncoder { "u/$it" }
            loader = CacheLoader { "user-$it" }
        }

        @Bean
        fun indexSpec(): SetCacheSpec<String, String> = setCacheSpec("index") { elementCodec = StringCodec }
    }

    @Test
    fun healthIndicatorRegistered() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasBean("cocacheHealthIndicator")
            val health = requireNotNull(context.getBean("cocacheHealthIndicator", HealthIndicator::class.java).health())
            health.status.assert().isEqualTo(Status.UP)
            health.details["backend"].assert().isEqualTo("InMemoryBackend")
            health.details["atomicBroadcast"].assert().isEqualTo(true)
            health.details["format"].assert().isEqualTo(InMemoryBackend.FORMAT)
            health.details["pendingInvalidations"].assert().isEqualTo(0)
            health.details.containsKey("heartbeat").assert().isFalse()
            @Suppress("UNCHECKED_CAST")
            val caches = health.details["caches"] as Map<String, CoCacheHealthIndicator.CacheHealth>
            caches.keys.assert().containsExactly("index", "user")
            caches.getValue("user").assert().isEqualTo(CoCacheHealthIndicator.CacheHealth(0, 0))
        }
    }

    @Test
    fun healthIndicatorCanBeDisabled() {
        runner.withPropertyValues("management.health.cocache.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CoCacheHealthIndicator::class.java)
        }
        runner.withClassLoader(FilteredClassLoader(HealthIndicator::class.java)).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CoCacheHealthIndicator::class.java)
        }
        runner.withPropertyValues("cocache.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CoCacheHealthIndicator::class.java)
        }
        ApplicationContextRunner().withConfiguration(autoConfigurations).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CoCacheHealthIndicator::class.java)
        }
    }

    private fun runtimeOf(backend: CoherenceBackend, admins: List<CacheAdmin> = emptyList()): CacheRuntime {
        val runtime = mockk<CacheRuntime>()
        val byName = admins.associateBy { it.name }
        every { runtime.backend } returns backend
        every { runtime.cacheNames } returns byName.keys
        byName.forEach { (name, admin) -> every { runtime.admin(name) } returns admin }
        return runtime
    }

    private fun redisBackend(healthy: Boolean): RedisBackend {
        val backend = mockk<RedisBackend>()
        every { backend.format } returns 1
        every { backend.atomicBroadcast } returns false
        every { backend.channelMode } returns RedisChannelMode.SHARDED
        every { backend.heartbeatHealthy } returns healthy
        every { backend.heartbeatFailure } returns if (healthy) null else "PING timed out"
        return backend
    }

    @Test
    fun healthIsDownOnlyWhenHeartbeatFails() {
        val admin = mockk<CacheAdmin>()
        every { admin.name } returns "user"
        every { admin.pendingInvalidationCount } returns 2
        every { admin.localSize } returns 7

        val up = requireNotNull(CoCacheHealthIndicator(runtimeOf(redisBackend(true), listOf(admin))).health())
        // 失效重试中不影响状态，只在详情中列出
        up.status.assert().withFailMessage { up.details.toString() }.isEqualTo(Status.UP)
        up.details["backend"].assert().isEqualTo("RedisBackend")
        up.details["channelMode"].assert().isEqualTo("sharded")
        up.details["atomicBroadcast"].assert().isEqualTo(false)
        up.details["heartbeat"].assert().isEqualTo(mapOf("healthy" to true))
        up.details["pendingInvalidations"].assert().isEqualTo(2)
        @Suppress("UNCHECKED_CAST")
        val userHealth = (up.details["caches"] as Map<String, CoCacheHealthIndicator.CacheHealth>).getValue("user")
        userHealth.pendingInvalidations.assert().isEqualTo(2)
        userHealth.localSize.assert().isEqualTo(7)

        val down = requireNotNull(CoCacheHealthIndicator(runtimeOf(redisBackend(false))).health())
        down.status.assert().isEqualTo(Status.DOWN)
        down.details["heartbeat"].assert().isEqualTo(mapOf("healthy" to false, "error" to "PING timed out"))
    }

    @Test
    fun healthWithRealRedisBackend() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataRedisAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java,
                    CoCacheHealthAutoConfiguration::class.java
                )
            )
            .withPropertyValues("cocache.redis.client-name=cocache-health-test")
            .run { context ->
                assertThat(context).hasNotFailed()
                val health = requireNotNull(context.getBean(CoCacheHealthIndicator::class.java).health())
                health.status.assert().isEqualTo(Status.UP)
                health.details["backend"].assert().isEqualTo("RedisBackend")
                health.details["channelMode"].assert().isEqualTo("pubsub")
            }
    }

    @Test
    fun endpointInspectsAndInvalidatesByEncodedKey() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val endpoint = context.getBean(CoCacheEndpoint::class.java)

            @Suppress("UNCHECKED_CAST")
            val user = context.getBean("user") as Cache<Long, String>
            user[1L].assert().isEqualTo("user-1")

            // 编码后的 key 含 '/'：剩余路径段按 '/' 拼回
            val inspection = requireNotNull(endpoint.inspect("user", arrayOf("u", "1")))
            inspection.storageKey.assert().isEqualTo("cocache:user:u/1")
            requireNotNull(inspection.local).state.assert().isEqualTo(KeyInspection.LocalState.VALUE)
            inspection.remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
            inspection.remote.value.assert().isEqualTo("user-1")
            endpoint.inspect("missing", arrayOf("u", "1")).assert().isNull()
            // 时间类型按 ISO-8601 输出（与文档示例一致）
            val json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(inspection)
            json.assert().contains("\"expiresAt\":\"20").contains("\"ttl\":\"PT").contains("\"state\":\"VALUE\"")

            val report = requireNotNull(endpoint.invalidate("user", arrayOf("u", "1")))
            report.cache.assert().isEqualTo("user")
            report.key.assert().isEqualTo("u/1")
            val after = requireNotNull(endpoint.inspect("user", arrayOf("u", "1")))
            after.local.assert().isNull()
            after.remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
            // 幂等；未知缓存返回 null（HTTP 上 GET 与 DELETE 都是 404，见 unknownCacheDeletesReturnNotFoundOverTheWeb）
            endpoint.invalidate("user", arrayOf("u", "1")).assert().isNotNull()
            endpoint.invalidate("missing", arrayOf("k")).assert().isNull()
            // 空 key（如结尾的 '/'）按非法请求拒绝，不会失效空串 key
            runCatching { endpoint.invalidate("user", emptyArray()) }.exceptionOrNull()
                .assert().isInstanceOf(
                    org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException::class.java
                )
            runCatching { endpoint.inspect("user", emptyArray()) }.exceptionOrNull()
                .assert().isInstanceOf(
                    org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException::class.java
                )

            @Suppress("UNCHECKED_CAST")
            val index = context.getBean("index") as SetCache<String, String>
            index.add("k", listOf("a"))
            requireNotNull(
                endpoint.inspect("index", arrayOf("k"))
            ).remote.state.assert().isEqualTo(KeyInspection.RemoteState.SET)
            endpoint.invalidate("index", arrayOf("k"))
            index["k"].assert().isEmpty()
        }
    }

    private fun webEndpoint(context: ApplicationContext): ExposableWebEndpoint {
        val discoverer = WebEndpointDiscoverer(
            context,
            ConversionServiceParameterValueMapper(),
            EndpointMediaTypes.DEFAULT,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        return discoverer.endpoints.first { it.endpointId.toString() == CoCacheEndpoint.ID }
    }

    @Test
    fun endpointOperationsAreDiscoveredForTheWeb() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val endpoint = webEndpoint(context)
            // web 扩展的 DELETE 替换端点上的同名操作，而不是并列注册
            val paths = endpoint.operations.map { "${it.requestPredicate.httpMethod} ${it.requestPredicate.path}" }
            paths.assert().containsExactlyInAnyOrder(
                "GET cocache",
                "GET cocache/{name}",
                "DELETE cocache/{name}",
                "GET cocache/{name}/{*key}",
                "DELETE cocache/{name}/{*key}",
            )
        }
    }

    /**
     * HTTP 上未知缓存的 DELETE 返回 404（6.9.1 及之前为 204，拼错的缓存名静默成功）；已知缓存照旧 204 / 200。
     */
    @Test
    fun unknownCacheDeletesReturnNotFoundOverTheWeb() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(CoCacheEndpointWebExtension::class.java)
            val operations = webEndpoint(context).operations.filter {
                it.requestPredicate.httpMethod == WebEndpointHttpMethod.DELETE
            }

            fun delete(path: String, arguments: Map<String, Any>): WebEndpointResponse<*> {
                val operation = operations.single { it.requestPredicate.path == path }
                return operation.invoke(InvocationContext(SecurityContext.NONE, arguments)) as WebEndpointResponse<*>
            }
            delete("cocache/{name}", mapOf("name" to "missing")).status.assert().isEqualTo(404)
            delete("cocache/{name}", mapOf("name" to "user")).status.assert().isEqualTo(204)
            delete(
                "cocache/{name}/{*key}",
                mapOf("name" to "missing", "key" to arrayOf("k"))
            ).status.assert().isEqualTo(404)
            val invalidated = delete("cocache/{name}/{*key}", mapOf("name" to "user", "key" to arrayOf("u", "1")))
            invalidated.status.assert().isEqualTo(200)
            invalidated.body.assert().isEqualTo(CoCacheEndpoint.InvalidationReport("user", "u/1"))

            val endpoint = context.getBean(CoCacheEndpoint::class.java)
            endpoint.clearLocal("user").assert().isTrue()
            endpoint.clearLocal("missing").assert().isFalse()
        }
    }

    /**
     * 只经 JMX 暴露时不注册 web 扩展：端点本身照常工作。
     */
    @Test
    fun webExtensionRequiresWebExposure() {
        ApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues("management.endpoints.jmx.exposure.include=cocache", "spring.jmx.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(CoCacheEndpoint::class.java)
                assertThat(context).doesNotHaveBean(CoCacheEndpointWebExtension::class.java)
            }
    }
}
