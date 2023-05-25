package me.ahoo.cache.spring.boot.starter

import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CoCacheEndpointAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withUserConfiguration(
                RedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoCacheEndpointAutoConfiguration::class.java
            )
            .run { context ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(CoCacheEndpoint::class.java)
                    .hasSingleBean(CoCacheClientEndpoint::class.java)
            }
    }
}
