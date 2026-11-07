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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * 关闭时等待在途 L1 步骤的上限跟随 `cocache.redis.command-timeout`（RFC 0005 §4.4、K17）：命令超时的两倍，不小于默认的 5 s。
 * 命令超时高于 5 s 时，关闭不会在在途的命令结束之前返回。
 */
class CloseTimeoutTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.backend=memory")

    private fun closeTimeoutWith(vararg properties: String): Duration {
        lateinit var timeout: Duration
        runner.withPropertyValues(*properties).run { context ->
            assertThat(context).hasNotFailed()
            timeout = context.getBean(CacheRuntime::class.java).closeTimeout
        }
        return timeout
    }

    @Test
    fun closeTimeoutFollowsTheCommandTimeout() {
        closeTimeoutWith().assert().isEqualTo(CacheRuntime.DEFAULT_CLOSE_TIMEOUT)
        closeTimeoutWith("cocache.redis.command-timeout=1s").assert().isEqualTo(CacheRuntime.DEFAULT_CLOSE_TIMEOUT)
        closeTimeoutWith("cocache.redis.command-timeout=10s").assert().isEqualTo(Duration.ofSeconds(20))
    }

    /**
     * Redis 后端按它实际的命令超时（选项定制器可能改过），经 `unwrap` 看穿包装（如 `HookedBackend`）。
     */
    @Test
    fun aWrappedRedisBackendUsesItsOwnCommandTimeout() {
        val redis = mockk<RedisBackend>()
        every { redis.commandTimeout } returns Duration.ofSeconds(7)
        every { redis.unwrap(RedisBackend::class.java) } returns redis
        val properties = Binder(MapConfigurationPropertySource(emptyMap<String, String>()))
            .bindOrCreate(CoCacheProperties.PREFIX, CoCacheProperties::class.java)
        CoCacheAutoConfiguration.closeTimeoutOf(properties.redis, HookedBackend(redis))
            .assert().isEqualTo(Duration.ofSeconds(14))
        CoCacheAutoConfiguration.closeTimeoutOf(properties.redis).assert().isEqualTo(CacheRuntime.DEFAULT_CLOSE_TIMEOUT)
    }
}
