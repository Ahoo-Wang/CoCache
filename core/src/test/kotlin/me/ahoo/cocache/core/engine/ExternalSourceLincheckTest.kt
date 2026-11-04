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
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.engine.LincheckSupport.CLOCK
import me.ahoo.cocache.core.engine.LincheckSupport.CONTEXT
import me.ahoo.cocache.core.engine.LincheckSupport.NAME
import me.ahoo.cocache.core.engine.LincheckSupport.options
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.Validate
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 外部来源的缓存（`externallySourced()`）上 `get` 与 `get(key, loader)` 交替（RFC 0005 §4.10，P3）：不带 loader 的读取得到
 * “未填充”（L1 中没有值），带 loader 的读取不接受它，用自己的 loader 回源；不带 loader 的读取接受带 loader 的加载的结果。
 *
 * 检验的是不变式而不是可线性化（[NotStale]）：外部来源的缓存上“没有”总是安全的答案（调用方随即自己回源），什么时候是“没有”
 * 取决于交错（loader 在提交与失效之间读到新版本时，栅栏让它不写入任何一层），顺序规格无法确定地给出。每次读取在开始时取一个下限
 * （之前完成的写入的版本，在写入方的锁内读取），读到的版本不得低于它（G2、读己之写）；带 loader 的读取不得没有值（P3：它不接受
 * “未填充”）。违反时操作返回规格从不返回的值。
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
class ExternalSourceLincheckTest {
    private val versions = AtomicIntegerArray(KEYS)
    private val writer = Any()
    private val store = InMemoryBackend(CLOCK).store(Keyspace("cocache:", NAME))
    private val loader = CacheLoader<String, String> { key -> "$key@${versions.get(key.toInt())}" }
    private val engine = CacheEngine(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            externallySourced()
            policy = CachePolicy(invalidateFailure = InvalidateFailureMode.THROW)
        }.engineSpec(),
        store,
        CONTEXT
    )

    /**
     * 不带 loader：没有值，或不低于下限的版本。
     */
    @Operation
    fun get(@Param(name = "key") key: Int): Int {
        val floor = floorOf(key)
        val value = engine[key.toString()] ?: return OK
        return checked(value, floor)
    }

    /**
     * 带 loader：总有值（P3），不低于下限。
     */
    @Operation
    fun getWithLoader(@Param(name = "key") key: Int): Int {
        val floor = floorOf(key)
        val value = engine.get(key.toString(), loader) ?: return UNFILLED
        return checked(value, floor)
    }

    @Operation
    fun write(@Param(name = "key") key: Int) = synchronized(writer) {
        versions.incrementAndGet(key)
        engine.invalidate(key.toString())
    }

    @Validate
    fun nothingLeft() = LincheckSupport.requireNoLease(store, KEYS)

    /**
     * 这次读取开始之前完成的写入的版本：写入方在锁内提交并失效，进行中的写入不计。
     */
    private fun floorOf(key: Int): Int = synchronized(writer) { versions.get(key) }

    /**
     * 读到的版本低于下限时返回它（取负，规格从不返回负数以外的 [OK]）。
     */
    private fun checked(value: String, floor: Int): Int {
        val version = value.substringAfter('@').toInt()
        return if (version >= floor) OK else STALE - version
    }

    @Test
    fun modelCheck() = options(NotStale::class.java)
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(2)
        .actorsPerThread(2)
        .check(this::class)

    /**
     * 定向场景：不带 loader 的读取与带 loader 的读取合并到彼此的加载（谁领头都穷举），以及写入之后两种读取。
     */
    @Test
    fun targetedInterleavings() = options(NotStale::class.java)
        .iterations(0)
        .invocationsPerIteration(TARGETED_INVOCATIONS)
        .addCustomScenario {
            parallel {
                thread { actor(ExternalSourceLincheckTest::get, 0) }
                thread { actor(ExternalSourceLincheckTest::getWithLoader, 0) }
            }
            post { actor(ExternalSourceLincheckTest::get, 0) }
        }
        .addCustomScenario {
            initial { actor(ExternalSourceLincheckTest::getWithLoader, 0) }
            parallel {
                thread { actor(ExternalSourceLincheckTest::getWithLoader, 0) }
                thread {
                    actor(ExternalSourceLincheckTest::write, 0)
                    actor(ExternalSourceLincheckTest::get, 0)
                }
            }
        }
        .check(this::class)

    /**
     * 顺序规格：任何读取都不陈旧（[OK]）；写入没有返回值。
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    class NotStale {
        fun get(key: Int): Int = OK

        fun getWithLoader(key: Int): Int = OK

        fun write(key: Int) = Unit
    }

    private companion object {
        const val KEYS = 2
        const val OK = 0

        /**
         * 带 loader 的读取没有值（接受了“未填充”）。
         */
        const val UNFILLED = -1

        /**
         * 陈旧的读取：`STALE - 读到的版本`。
         */
        const val STALE = -100
        const val ITERATIONS = 5
        const val INVOCATIONS = 150
        const val TARGETED_INVOCATIONS = 500
    }
}
