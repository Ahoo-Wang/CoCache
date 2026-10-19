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

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

class CoCacheObservationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ObservationAutoConfiguration::class.java,
                CoCacheObservationAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
            )
        )
        .withUserConfiguration(InMemoryConfig::class.java)

    /**
     * 记录 Observation 生命周期的处理器。
     */
    class RecordingHandler : ObservationHandler<Observation.Context> {
        val started = CopyOnWriteArrayList<Observation.Context>()
        val stopped = CopyOnWriteArrayList<Observation.Context>()

        override fun onStart(context: Observation.Context) {
            started.add(context)
        }

        override fun onStop(context: Observation.Context) {
            stopped.add(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    @Configuration(proxyBeanMethods = false)
    class InMemoryConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader {
                check(it != "bad") { "source down" }
                "user-$it"
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class TestRegistryConfig {
        @Bean
        fun handler(): RecordingHandler = RecordingHandler()

        /**
         * 校验 Observation 生命周期的注册表；Spring Boot 把 [RecordingHandler] 等 `ObservationHandler` bean 挂到它上面。
         */
        @Bean
        fun observationRegistry(): ObservationRegistry = TestObservationRegistry.create()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomObserverConfig {
        @Bean
        fun customObserver(): RecordingObserver = RecordingObserver()
    }

    /**
     * 用户自定义的观测器：记录操作类型。
     */
    class RecordingObserver : LoadObserver {
        val operations = CopyOnWriteArrayList<LoadOperation>()

        override fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T {
            operations.add(operation)
            return block.get()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ApplicationContext.user(): Cache<String, String> = getBean("user") as Cache<String, String>

    private fun Observation.Context.tag(key: String): String? = getLowCardinalityKeyValue(key)?.value

    @Test
    fun tracesSourceLoadsButNotHits() {
        runner.withUserConfiguration(TestRegistryConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(MicrometerLoadObserver::class.java)
            val handler = context.getBean(RecordingHandler::class.java)
            val user = context.user()

            user["alice"].assert().isEqualTo("user-alice")
            user["alice"].assert().isEqualTo("user-alice")
            user.getAll(listOf("alice", "2", "3")).assert().hasSize(3)

            handler.started.assert().hasSize(2)
            handler.stopped.assert().isEqualTo(handler.started)
            val load = handler.stopped.first()
            load.name.assert().isEqualTo(MicrometerLoadObserver.NAME)
            load.contextualName.assert().isEqualTo("cocache load user")
            load.tag(MicrometerLoadObserver.CACHE).assert().isEqualTo("user")
            load.tag(MicrometerLoadObserver.OPERATION).assert().isEqualTo("load")
            load.tag(MicrometerLoadObserver.OUTCOME).assert().isEqualTo(MicrometerLoadObserver.SUCCESS)
            load.getHighCardinalityKeyValue(MicrometerLoadObserver.KEY_COUNT)?.value.assert().isEqualTo("1")
            load.error.assert().isNull()
            // 从不记录 key
            val values = (load.lowCardinalityKeyValues + load.highCardinalityKeyValues).map { it.value }
            values.assert().noneMatch { it.contains("alice") }
            load.lowCardinalityKeyValues.map { it.key }.assert()
                .containsExactlyInAnyOrder(
                    MicrometerLoadObserver.CACHE,
                    MicrometerLoadObserver.OPERATION,
                    MicrometerLoadObserver.OUTCOME
                )

            val batch = handler.stopped[1]
            batch.contextualName.assert().isEqualTo("cocache loadAll user")
            batch.tag(MicrometerLoadObserver.OPERATION).assert().isEqualTo("loadAll")
            batch.getHighCardinalityKeyValue(MicrometerLoadObserver.KEY_COUNT)?.value.assert().isEqualTo("2")
        }
    }

    @Test
    fun recordsLoaderErrorsAndRethrowsThem() {
        runner.withUserConfiguration(TestRegistryConfig::class.java).run { context ->
            val handler = context.getBean(RecordingHandler::class.java)
            val error = assertThrows<IllegalStateException> { context.user()["bad"] }
            error.message.assert().isEqualTo("source down")
            val failed = handler.stopped.single()
            failed.tag(MicrometerLoadObserver.OUTCOME).assert().isEqualTo(MicrometerLoadObserver.ERROR)
            failed.error.assert().isSameAs(error)
        }
    }

    @Test
    fun noopRegistryRunsTheBlockDirectly() {
        val noop = MicrometerLoadObserver(ObservationRegistry.NOOP)
        noop.observe("c", LoadOperation.LOAD, 1) { "v" }.assert().isEqualTo("v")
        // 没有处理器的注册表同样是 no-op
        val empty = MicrometerLoadObserver(ObservationRegistry.create())
        empty.observe("c", LoadOperation.REFRESH, 1) { "v" }.assert().isEqualTo("v")
    }

    @Test
    fun usesSpringBootObservationRegistryAndHonorsItsFilters() {
        runner.withBean(RecordingHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(MicrometerLoadObserver::class.java)
            context.user()["1"]
            context.getBean(RecordingHandler::class.java).stopped.single().name.assert().isEqualTo("cocache.load")
        }
        runner.withBean(RecordingHandler::class.java)
            .withPropertyValues("management.observations.enable.cocache=false")
            .run { context ->
                context.user()["1"]
                context.getBean(RecordingHandler::class.java).started.assert().isEmpty()
            }
    }

    @Test
    fun noRegistryMeansNoObserver() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CoCacheObservationAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java
                )
            )
            .withUserConfiguration(InMemoryConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(LoadObserver::class.java)
                context.user()["1"].assert().isEqualTo("user-1")
            }
    }

    @Test
    fun canBeDisabled() {
        runner.withUserConfiguration(TestRegistryConfig::class.java)
            .withPropertyValues("cocache.observation.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(LoadObserver::class.java)
                context.user()["1"]
                context.getBean(RecordingHandler::class.java).started.assert().isEmpty()
            }
        runner.withUserConfiguration(TestRegistryConfig::class.java)
            .withPropertyValues("cocache.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(LoadObserver::class.java)
            }
    }

    @Test
    fun backsOffWithoutMicrometerObservation() {
        runner.withClassLoader(FilteredClassLoader("io.micrometer.observation")).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(LoadObserver::class.java)
            context.user()["1"].assert().isEqualTo("user-1")
        }
    }

    @Test
    fun userObserverReplacesMicrometerObserver() {
        runner.withUserConfiguration(TestRegistryConfig::class.java, CustomObserverConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(MicrometerLoadObserver::class.java)
            context.user()["1"]
            context.getBean(RecordingObserver::class.java).operations.assert().containsExactly(LoadOperation.LOAD)
            context.getBean(RecordingHandler::class.java).started.assert().isEmpty()
        }
    }
}
