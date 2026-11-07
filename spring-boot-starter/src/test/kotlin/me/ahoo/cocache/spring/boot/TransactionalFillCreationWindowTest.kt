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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch

/**
 * 缓存创建期间（发布之后、写事务内回源的模式生效之前）开始的加载（#868）：应用自建运行时并先注册创建监听器，
 * 仍使用 starter 的回源钩子、工厂与配置分层。这次加载自始至终取同一组模式（进入时的类型默认值），不与之后生效的
 * `private` / `invalidate-fills-on-rollback: false` 混用：否则默认值放行了共享填充，生效值又跳过了回滚失效，回滚之后未提交的值留在 L1。
 */
class TransactionalFillCreationWindowTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java)
        .withPropertyValues(
            "cocache.backend=memory",
            "cocache.defaults.cache.fill-in-transaction=private",
            "cocache.defaults.cache.invalidate-fills-on-rollback=false",
        )

    data class Observation(val loaded: String?, val inspection: KeyInspection)

    class Probe {
        @Volatile
        var readDuringCreation = false
        val sourceEntered = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)

        @Volatile
        var worker: Concurrency.Forked<Observation>? = null

        @Volatile
        var failure: Throwable? = null

        fun load(rows: TransactionalFillTest.Rows, key: String): String? {
            val value = rows.read(key)
            if (readDuringCreation) {
                sourceEntered.countDown()
                releaseSource.awaitOrFail("factory has returned and committed its transaction modes")
            }
            return value
        }

        @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
        fun created(runtime: CacheRuntime, admin: CacheAdmin, rows: TransactionalFillTest.Rows) {
            if (!readDuringCreation || admin.name != NAME) {
                return
            }
            try {
                // 经公开的查找取得已发布的缓存，不借用工厂尚未返回的引用
                val cache = requireNotNull(runtime.cache(admin.name)) as Cache<String, String>
                worker = Concurrency.fork("creation-window-transaction") { observeDraft(cache, admin, rows) }
                // loader 已进入：引擎已经按进入时的模式决定这次回源是否私有
                sourceEntered.awaitOrFail("source entered before committing the configured modes")
            } catch (e: Throwable) {
                // 运行时只记录创建监听器的异常，必须在测试主体里检查
                failure = e
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Config {
        @Bean
        fun rows(): TransactionalFillTest.Rows = TransactionalFillTest.Rows()

        @Bean
        fun probe(): Probe = Probe()

        @Bean(destroyMethod = "close")
        fun runtime(
            backend: CoherenceBackend,
            observer: FillObserver,
            rows: TransactionalFillTest.Rows,
            probe: Probe
        ): CacheRuntime =
            CacheRuntime.builder().backend(backend).fillObserver(observer).build().also { runtime ->
                // 运行时 bean 返回之前注册：CacheAssembler 的模式提交监听器排在它之后
                runtime.addCreationListener { admin -> probe.created(runtime, admin, rows) }
            }
    }

    /**
     * 缺陷复现（#868）：创建监听器在模式生效之前发起的写事务加载，回滚之后 L1 与 L2 都不留下未提交的值。
     */
    @Test
    fun aLoadStartedDuringCreationDoesNotKeepARolledBackValue() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val rows = context.getBean(TransactionalFillTest.Rows::class.java)
            val runtime = context.getBean(CacheRuntime::class.java)
            val probe = context.getBean(Probe::class.java)
            probe.readDuringCreation = true
            try {
                context.getBean(CoCacheFactory::class.java).create(spec(rows, probe))
                probe.failure.assert().isNull()
                // 创建已返回，private / false 已生效；这时才让按进入时的模式开始的加载写回
                probe.releaseSource.countDown()
                val observation = requireNotNull(probe.worker).join()
                observation.loaded.assert().isEqualTo(DRAFT)
                rows.read(KEY).assert().isNull()
                // 事务已回滚：两层都不保留未提交的值
                assertNothingShared(requireNotNull(runtime.admin(NAME)).inspect(KEY))
            } finally {
                probe.releaseSource.countDown()
                probe.worker?.join()
            }
        }
    }

    /**
     * 对照：创建返回之后开始的加载按生效的 `private` 私有回源，不写入 L1 与 L2。
     */
    @Test
    fun aLoadAfterCreationUsesTheConfiguredModes() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val rows = context.getBean(TransactionalFillTest.Rows::class.java)
            val runtime = context.getBean(CacheRuntime::class.java)
            val probe = context.getBean(Probe::class.java)
            val cache = context.getBean(CoCacheFactory::class.java).create(spec(rows, probe))
            val observation = observeDraft(cache, requireNotNull(runtime.admin(NAME)), rows)
            observation.loaded.assert().isEqualTo(DRAFT)
            // 事务内的快照：私有回源，没有写入任何共享层
            assertNothingShared(observation.inspection)
        }
    }

    private fun assertNothingShared(inspection: KeyInspection) {
        inspection.remote.assert().isEqualTo(KeyInspection.RemoteCopy(KeyInspection.RemoteState.MISS))
        inspection.local.assert().isNull()
    }

    private fun spec(rows: TransactionalFillTest.Rows, probe: Probe) = cacheSpec<String, String>(NAME) {
        codec = StringCodec
        loader = CacheLoader { key -> probe.load(rows, key) }
    }

    companion object {
        private const val NAME = "listener-private"
        private const val KEY = "customer-1"
        private const val DRAFT = "uncommitted-draft"

        private fun observeDraft(cache: Cache<String, String>, admin: CacheAdmin, rows: TransactionalFillTest.Rows): Observation {
            var observed: Observation? = null
            TransactionTemplate(rows).executeWithoutResult { status ->
                rows.write(KEY, DRAFT)
                val loaded = cache[KEY]
                // 回滚失效之前的快照：事务内实际写入了什么
                observed = Observation(loaded, admin.inspect(KEY))
                status.setRollbackOnly()
            }
            return requireNotNull(observed)
        }
    }
}
