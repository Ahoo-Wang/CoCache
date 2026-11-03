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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.test.Suspending
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.forClasses
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Lincheck 模型检验（RFC 0005 §4.10、Q18，可行性见 docs/investigations/lincheck-feasibility.md）：真实的
 * “flight 层 + L2（Caffeine）+ 失效戳 + 内存存储”，经同步 API 调用。
 *
 * 顺序规格是“带版本的数据源”（[VersionedSource]）：写入 = 数据源提交新版本，再 `invalidate`；读取 = 当前版本。
 * 单节点上它是可线性化的：读到的版本在读取期间的某个时刻是当前版本，`invalidate` 返回之后的读取不会读到更旧的版本（读己之写）。
 * 另一个节点的写入以“提交 + 删除 L1 + 投递失效事件”模拟。
 *
 * 不在 `test` 与 `check` 中（约 2 分钟），单独运行：`./gradlew :cocache-core:lincheck`（关闭 JaCoCo 与 kotlinx 调试模式，见构建脚本）。
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
class CoherentEngineLincheckTest {
    private val versions = AtomicIntegerArray(KEYS)
    private val store = InMemoryBackend(CLOCK).store(NAME)
    private val engine = CoherentEngine(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            loader = CacheLoader { key -> "$key@${versions.get(key.toInt())}" }
            policy = CachePolicy(invalidateFailure = InvalidateFailureMode.THROW)
        },
        store,
        CONTEXT
    )

    @Operation
    fun get(@Param(name = "key") key: Int): Int = requireNotNull(engine[key.toString()]).substringAfter('@').toInt()

    @Operation
    fun write(@Param(name = "key") key: Int) {
        versions.incrementAndGet(key)
        engine.invalidate(key.toString())
    }

    /**
     * 另一个节点的写入：提交，删除 L1（同时广播），失效事件随即投递到本节点。
     */
    @Operation
    fun remoteWrite(@Param(name = "key") key: Int) {
        versions.incrementAndGet(key)
        val storageKey = "cocache:$NAME:$key"
        Suspending.await { store.invalidate(listOf(storageKey), InvalidationFrame.encode(OTHER, listOf(storageKey))) }
        engine.onInvalidated(listOf(storageKey), OTHER)
    }

    /**
     * 随机场景：两个线程各两个操作。能发现去掉 L2 填充的戳校验、忽略别的节点的失效。
     */
    @Test
    fun modelCheck() = options()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(2)
        .actorsPerThread(2)
        .check(this::class)

    /**
     * 定向场景：对两个已知的竞态穷举交错。
     * - 读取合并到写入开始之前就在进行的加载（读己之写的复核）；
     * - 写入方修改 L1 期间，并发的读取从旧的 L1 读到值、在写入返回之后才填 L2（写入方的第二次淘汰）。
     */
    @Test
    fun targetedInterleavings() = options()
        .iterations(0)
        .invocationsPerIteration(TARGETED_INVOCATIONS)
        .addCustomScenario {
            parallel {
                thread { actor(CoherentEngineLincheckTest::get, 0) }
                thread {
                    actor(CoherentEngineLincheckTest::write, 0)
                    actor(CoherentEngineLincheckTest::get, 0)
                }
            }
        }
        .addCustomScenario {
            initial { actor(CoherentEngineLincheckTest::get, 0) }
            parallel {
                thread {
                    actor(CoherentEngineLincheckTest::write, 0)
                    actor(CoherentEngineLincheckTest::get, 0)
                }
                thread { actor(CoherentEngineLincheckTest::get, 0) }
            }
        }
        .check(this::class)

    /**
     * 共用的选项。不加这些保证时，Caffeine 的内部缓冲与 `LongAdder` 的分段竞争让状态空间爆炸、执行被判定为挂起且无法重放：
     * - Caffeine 的每个调用当作原子步骤（`compute` 与 `clear` 的语义另以定向用例覆盖，C4）；
     * - 计数（`LongAdder`）与日志与交错无关，忽略。
     */
    private fun options(): ModelCheckingOptions = ModelCheckingOptions()
        .sequentialSpecification(VersionedSource::class.java)
        .addGuarantee(forClasses { it.startsWith(CAFFEINE) }.allMethods().treatAsAtomic())
        .addGuarantee(forClasses { name -> IGNORED.any { name.startsWith(it) } }.allMethods().ignore())

    /**
     * 顺序规格：带版本的数据源。
     */
    class VersionedSource {
        private val versions = IntArray(KEYS)

        fun get(key: Int): Int = versions[key]

        fun write(key: Int) {
            versions[key]++
        }

        fun remoteWrite(key: Int) {
            versions[key]++
        }
    }

    companion object {
        private const val NAME = "lincheck"
        private const val KEYS = 2
        private const val ITERATIONS = 10
        private const val INVOCATIONS = 300
        private const val TARGETED_INVOCATIONS = 2000
        private const val CAFFEINE = "com.github.benmanes.caffeine."
        private val IGNORED = listOf(
            "java.util.concurrent.atomic.LongAdder",
            "java.util.concurrent.atomic.Striped64",
            "io.github.oshai.",
            "org.slf4j.",
            "ch.qos.logback.",
        )
        private val CLOCK = CacheClock { 1_700_000_000_000L }
        private val OTHER = NodeId(ByteArray(NodeId.SIZE) { 7 })
        private val DIRECT = Executor { it.run() }

        /**
         * 所有调用共享的运行时资源（模型检验每次调用新建引擎与存储）：固定的时钟与计时源让每次执行可以重放。
         */
        private val CONTEXT = EngineContext(
            NodeId(ByteArray(NodeId.SIZE) { 1 }),
            CLOCK,
            InMemoryBackend(CLOCK),
            DIRECT,
            InvalidationRetrier(
                Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } },
                RetryPolicy()
            ),
            LoadObserver.NOOP,
            AsyncContext(DIRECT, DIRECT),
            ticker = Ticker { 0L },
        )
    }
}
