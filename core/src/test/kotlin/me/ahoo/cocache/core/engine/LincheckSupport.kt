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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.FillKey
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.FillScope
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.internal.InvalidationStamps
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Suspending
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.forClasses
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Lincheck 模型检验（RFC 0005 §4.10）共用的设置：固定的时钟与计时源、共享的引擎上下文、缩小检验范围的保证，以及几种调用方。
 */
internal object LincheckSupport {
    const val NAME = "lincheck"

    /**
     * 完整的预算（`./gradlew :cocache-core:lincheck -Plincheck.full`，本地约 5 分钟）；默认是 CI 的预算（本地约 1 分钟）：
     * 场景都相同，只是每个场景的执行次数与随机场景的轮数少。
     */
    private val FULL: Boolean = System.getProperty("cocache.lincheck.full").toBoolean()

    /**
     * 按 [FULL] 取预算：完整时 [full]，否则 [ci]。
     */
    fun budget(full: Int, ci: Int): Int = if (FULL) full else ci

    val CLOCK = CacheClock { 1_700_000_000_000L }
    val OTHER = NodeId(ByteArray(NodeId.SIZE) { 7 })
    private val DIRECT = Executor { it.run() }

    /**
     * 当前线程所在的写事务（RFC 0005 §4.9）：只有在 [inTransaction] 中的同步读取经过门控与记录。
     */
    private val TRANSACTION = ThreadLocal<FillScope?>()

    /**
     * 所有调用共享的运行时资源（模型检验每次调用新建引擎与存储）：固定的时钟与计时源让每次执行可以重放。
     */
    val CONTEXT = context(FillObserver.NONE)

    /**
     * 同 [CONTEXT]，同步读取在入口取 [inTransaction] 设置的写事务（RFC 0005 §4.9）。只用于检验写事务的类：
     * 每次未命中多一次线程局部变量的读取，增加模型检验的事件数。
     */
    val TX_CONTEXT = context(FillObserver { TRANSACTION.get() })

    private fun context(fillObserver: FillObserver) = EngineContext(
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
        fillObserver = fillObserver,
    )

    private const val CAFFEINE = "com.github.benmanes.caffeine."
    private val STAMPS = InvalidationStamps::class.java.name
    private val IGNORED = listOf(
        "java.util.concurrent.atomic.LongAdder",
        "java.util.concurrent.atomic.Striped64",
        "io.github.oshai.",
        "org.slf4j.",
        "ch.qos.logback.",
    )

    /**
     * 共用的选项。不加这些保证时，Caffeine 的内部缓冲与 `LongAdder` 的分段竞争让状态空间爆炸、执行被判定为挂起且无法重放：
     * - Caffeine 的每个调用当作原子步骤（`compute` 与 `clear` 的语义另以定向用例覆盖，C4）；
     * - `InvalidationStamps.invalidateAll`（`clearLocal`、`onReset`）逐个递增 4096 个分段，当作一步：读取方只看自己 key 所在的分段，
     *   与“一次递增全部分段”能观察到的结局相同；
     * - 计数（`LongAdder`）与日志与交错无关，忽略。
     */
    fun options(spec: Class<*>): ModelCheckingOptions = ModelCheckingOptions()
        .sequentialSpecification(spec)
        .addGuarantee(forClasses { it.startsWith(CAFFEINE) }.allMethods().treatAsAtomic())
        .addGuarantee(forClasses(STAMPS).methods("invalidateAll").treatAsAtomic())
        .addGuarantee(forClasses { name -> IGNORED.any { name.startsWith(it) } }.allMethods().ignore())

    fun storageKey(key: Int): String = "cocache:$NAME:$key"

    /**
     * 在写事务中执行 [block]（RFC 0005 §4.9）：[private] 时这次调用的回源都是私有的，否则只是“事务中的 leader 不共享结果”。
     * 每次调用一个新的事务。
     */
    fun <T> inTransaction(private: Boolean, block: () -> T): T {
        TRANSACTION.set(Transaction(private))
        try {
            return block()
        } finally {
            TRANSACTION.remove()
        }
    }

    /**
     * 同步变体的调用方被中断（RFC 0005 §4.3：同步变体的取消就是中断）：线程带着中断标志调用 [block]。
     * 结局取决于交错（命中照常返回，回源之前以 `InterruptedException` 结束），因此只执行、不返回；之后清除中断标志。
     */
    fun interrupted(block: () -> Unit) {
        Thread.currentThread().interrupt()
        try {
            block()
        } catch (_: InterruptedException) {
            // 预期的结局之一
        } finally {
            Thread.interrupted()
        }
    }

    /**
     * 挂起变体的调用方被取消：启动 [block]，在它第一次挂起（合并到别的调用方的加载）时取消。内存后端不挂起，
     * 不合并时 [block] 在取消之前就已完成。只等待调用方结束，不返回结果。
     */
    fun cancelled(block: suspend () -> Unit) {
        runBlocking {
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { block() }
            caller.cancel()
            caller.join()
        }
    }

    /**
     * L1 上没有留下 lease：失败、被中断或被取消的调用都交还了令牌（否则之后的读取要等 lease）。
     */
    fun requireNoLease(store: L1Store, keys: Int) {
        repeat(keys) { key ->
            val read = Suspending.await { store.read(storageKey(key), null, null, null) }
            check(read !is L1Read.Leased) { "a lease was left on key[$key]: $read" }
        }
    }

    private class Transaction(private val private: Boolean) : FillScope {
        override fun isPrivate(key: FillKey): Boolean = private

        override fun filled(key: FillKey) = Unit
    }
}
