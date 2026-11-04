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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.engine.LincheckSupport.CLOCK
import me.ahoo.cocache.core.engine.LincheckSupport.NAME
import me.ahoo.cocache.core.engine.LincheckSupport.OTHER
import me.ahoo.cocache.core.engine.LincheckSupport.TX_CONTEXT
import me.ahoo.cocache.core.engine.LincheckSupport.options
import me.ahoo.cocache.core.engine.LincheckSupport.storageKey
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.test.Suspending
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.Validate
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * RFC 0005 §4.10 的完整操作集（[CoherentEngineLincheckTest] 只有 `get`、`invalidate` 与别的节点的写入）：
 * `get`、`get(key, loader)`（P3）、`getAll`（与单 key 的 flight 合并，含写事务中的 leader 与私有回源，P10、§4.9）、
 * `invalidate`、`put`、`onInvalidated`、`onReset`、`clearLocal`，以及调用方取消（同步变体被中断、挂起变体被取消）。
 *
 * 顺序规格同样是“带版本的数据源 + 读己之写”（[Operations]）。写入方之间按行加锁（[writer]）：`put` 是后写者胜，
 * 两个写入方各自提交、再无序地写缓存时，任何缓存都会留下较旧的值，这不是这里要检验的性质。
 *
 * 私有回源直接读数据源，在写入方提交与失效之间读到新版本、随后的普通读取读到缓存中的旧版本，与任何直接读数据库的读取一样，
 * 不可线性化为“带版本的数据源”。真实的私有回源只读本事务正在写的 key，所以 [getPrivate] 只检验它对其它读取的影响
 * （不写任何共享层、不留下 lease），不检验它自己的结果。
 *
 * 操作多，每次执行比 [CoherentEngineLincheckTest] 慢得多：随机场景的预算小，主要靠定向场景。
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
class EngineOperationsLincheckTest {
    private val versions = AtomicIntegerArray(KEYS + 1)
    private val writer = Any()
    private val store = InMemoryBackend(CLOCK).store(Keyspace("cocache:", NAME))
    private val loader = CacheLoader<String, String> { key -> "$key@${versions.get(key.toInt())}" }
    private val engine = CacheEngine(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            loader = this@EngineOperationsLincheckTest.loader
            policy = CachePolicy(invalidateFailure = InvalidateFailureMode.THROW)
        }.engineSpec(),
        store,
        TX_CONTEXT
    )

    @Operation
    fun get(@Param(name = "key") key: Int): Int = versionOf(engine[key.toString()])

    /**
     * 调用级 loader（P3）：与 `get` 合并到同一次加载，带数据源的调用方只接受带数据源的结果。
     */
    @Operation
    fun getWithLoader(@Param(name = "key") key: Int): Int = versionOf(engine.get(key.toString(), loader))

    /**
     * 批量读取（P10）：[key] 与一个从不写入的 key（[STATIC]）一起读，与单 key 的 flight 逐 key 合并。只有 [key] 可能并发写入，
     * 结果因此是一次原子读取。
     */
    @Operation
    fun getAll(@Param(name = "key") key: Int): Int {
        val values = engine.getAll(listOf(key.toString(), STATIC.toString()))
        return if (versionOf(values[STATIC.toString()]) == 0) versionOf(values[key.toString()]) else MIXED
    }

    @Operation
    fun write(@Param(name = "key") key: Int) = synchronized(writer) {
        versions.incrementAndGet(key)
        engine.invalidate(key.toString())
    }

    /**
     * 提交并写穿透（`put`，后写者胜：与其它写入方按行加锁）。
     */
    @Operation
    fun put(@Param(name = "key") key: Int) = synchronized(writer) {
        val version = versions.incrementAndGet(key)
        engine.put(key.toString(), "$key@$version")
    }

    /**
     * 另一个节点的写入：提交，删除 L1（同时广播），失效事件随即投递到本节点（`onInvalidated`）。
     */
    @Operation
    fun remoteWrite(@Param(name = "key") key: Int) = synchronized(writer) {
        versions.incrementAndGet(key)
        val storageKey = storageKey(key)
        Suspending.await { store.invalidate(listOf(storageKey), InvalidationFrame.encode(OTHER, listOf(storageKey))) }
        engine.onInvalidated(listOf("$key"), OTHER)
    }

    @Operation
    fun clearLocal() = engine.clearLocal()

    /**
     * 订阅重置（`onReset`）：清空 L2、递增全部戳。
     */
    @Operation
    fun reset() = engine.onReset()

    /**
     * 写事务中的私有回源（§4.9 机制 2）：不读不写 L1 与 L2、不经 SingleFlight。结果不检验（见类注释）。
     */
    @Operation
    fun getPrivate(@Param(name = "key") key: Int) {
        LincheckSupport.inTransaction(private = true) { engine[key.toString()] }
    }

    /**
     * 写事务中的读取：作为 leader 时结果不交给跟随者（S15）。
     */
    @Operation
    fun getInTransaction(
        @Param(
            name = "key"
        ) key: Int
    ): Int = LincheckSupport.inTransaction(private = false) { get(key) }

    /**
     * 同步变体的调用方被中断：作为 leader 时交还 lease，跟随者自行回源。
     */
    @Operation
    fun interruptedGet(@Param(name = "key") key: Int) = LincheckSupport.interrupted { engine[key.toString()] }

    /**
     * 挂起变体的调用方被取消：只结束它自己的等待，共享的加载照常完成。
     */
    @Operation
    fun cancelledGet(
        @Param(
            name = "key"
        ) key: Int
    ) = LincheckSupport.cancelled { engine.suspending().get(key.toString()) }

    @Validate
    fun nothingLeft() = LincheckSupport.requireNoLease(store, KEYS)

    private fun versionOf(value: String?): Int = requireNotNull(value).substringAfter('@').toInt()

    /**
     * 随机场景：两个线程各两个操作，预算小（操作多，每次执行慢）。
     */
    @Test
    fun modelCheck() = options(Operations::class.java)
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(2)
        .actorsPerThread(2)
        .check(this::class)

    /**
     * 定向场景：`get(key, loader)` 与 `getAll` 合并到写入开始之前的加载，或被写入开始之后的读取合并：
     * 读己之写的复核逐 key 生效（P3、P10）。
     */
    @Test
    fun readInterleavings() = targeted(OPERATION_INVOCATIONS)
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::get, 0) }
                thread {
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::getWithLoader, 0)
                }
            }
        }
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::get, 0) }
                thread {
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::getAll, 0)
                }
            }
        }
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::getAll, 0) }
                thread {
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::get, 0)
                }
            }
        }
        .check(this::class)

    /**
     * 定向场景：
     * - `put` 修改 L1 期间并发的读取（写入方的第二次淘汰，`put` 的版本）；
     * - 写事务中的 leader 与批量读取、私有回源与写入并发（§4.9）；
     * - `onReset` 与 `clearLocal` 与写入、读取并发。
     */
    @Test
    fun writeInterleavings() = targeted(OPERATION_INVOCATIONS)
        .addCustomScenario {
            initial { actor(EngineOperationsLincheckTest::get, 0) }
            parallel {
                thread {
                    actor(EngineOperationsLincheckTest::put, 0)
                    actor(EngineOperationsLincheckTest::get, 0)
                }
                thread { actor(EngineOperationsLincheckTest::get, 0) }
            }
        }
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::getInTransaction, 0) }
                thread {
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::getAll, 0)
                }
            }
        }
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::getPrivate, 0) }
                thread {
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::get, 0)
                }
            }
        }
        .addCustomScenario {
            initial { actor(EngineOperationsLincheckTest::get, 0) }
            parallel {
                thread {
                    actor(EngineOperationsLincheckTest::reset)
                    actor(EngineOperationsLincheckTest::get, 0)
                }
                thread {
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::clearLocal)
                }
            }
        }
        .check(this::class)

    /**
     * 定向场景：调用方取消。
     * - 被中断的同步调用方作为 leader：交还 lease，合并到它的读取自行回源；
     * - 被取消的挂起调用方作为跟随者：只结束它自己的等待，之后的写入与读取照常（读己之写）。
     * 之后读到最新的版本，L1 上没有留下 lease（[nothingLeft]）。
     */
    @Test
    fun callerCancellation() = targeted(CANCELLATION_INVOCATIONS)
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::interruptedGet, 0) }
                thread { actor(EngineOperationsLincheckTest::get, 0) }
            }
            post { actor(EngineOperationsLincheckTest::get, 0) }
        }
        .addCustomScenario {
            parallel {
                thread { actor(EngineOperationsLincheckTest::get, 0) }
                thread {
                    actor(EngineOperationsLincheckTest::cancelledGet, 0)
                    actor(EngineOperationsLincheckTest::write, 0)
                    actor(EngineOperationsLincheckTest::get, 0)
                }
            }
        }
        .check(this::class)

    private fun targeted(invocations: Int): ModelCheckingOptions = options(Operations::class.java)
        .iterations(0)
        .invocationsPerIteration(invocations)

    /**
     * 顺序规格：带版本的数据源 + 读己之写。读取（任何形式）是当前版本；提交是版本加一；只动本节点副本的操作、私有回源
     * 与被取消的调用没有可检验的效果。
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    class Operations {
        private val versions = IntArray(KEYS)

        fun get(key: Int): Int = versions[key]

        fun getWithLoader(key: Int): Int = versions[key]

        fun getAll(key: Int): Int = versions[key]

        fun write(key: Int) {
            versions[key]++
        }

        fun put(key: Int) {
            versions[key]++
        }

        fun remoteWrite(key: Int) {
            versions[key]++
        }

        fun clearLocal() = Unit

        fun reset() = Unit

        fun getPrivate(key: Int) = Unit

        fun getInTransaction(key: Int): Int = versions[key]

        fun interruptedGet(key: Int) = Unit

        fun cancelledGet(key: Int) = Unit
    }

    private companion object {
        const val KEYS = 2

        /**
         * [getAll] 一起读的 key：从不写入，版本总是 0。
         */
        const val STATIC = KEYS

        /**
         * [getAll] 读到的 [STATIC] 不是 0：顺序规格从不返回它。
         */
        const val MIXED = -2
        const val ITERATIONS = 3
        const val INVOCATIONS = 100
        const val OPERATION_INVOCATIONS = 300
        const val CANCELLATION_INVOCATIONS = 500
    }
}
