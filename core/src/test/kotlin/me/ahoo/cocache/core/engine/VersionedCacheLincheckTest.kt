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
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.engine.LincheckSupport.CLOCK
import me.ahoo.cocache.core.engine.LincheckSupport.CONTEXT
import me.ahoo.cocache.core.engine.LincheckSupport.NAME
import me.ahoo.cocache.core.engine.LincheckSupport.options
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.versionedCacheSpec
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.Validate
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 带版本的派生副本 `VersionedCache`（RFC 0005 §4.7、§4.10）在单节点上的交错，顺序规格“带版本的写入方 + 读快照的 loader”
 * （[VersionedWriter]）：写入方提交新版本，再 `putIfNewer` 新值或 `invalidateIfNewer`（墓碑）；loader 读数据源当时的快照，
 * 可能落后于并发的写入。`evict` 换成保留版本栅栏的 `E`，之后的回源不能写回更旧的版本；`clearLocal` 只动本节点副本。
 *
 * 写入方之间不加锁：版本有序的写入在 L1 中原子地比较，迟到的旧版本被拒绝，任何交错都留下最大的版本。
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
class VersionedCacheLincheckTest {
    /**
     * 每个 key 的数据源状态：`版本 * 2`，已删除时再加一。读快照的 loader 一次读出版本与是否删除。
     */
    private val source = AtomicIntegerArray(KEYS)
    private val store = InMemoryBackend(CLOCK).store(Keyspace("cocache:", NAME))
    private val engine = VersionedCacheEngine(
        versionedCacheSpec<String, String>(NAME) {
            codec = StringCodec
            versionedLoader = CacheLoader { key ->
                val state = source.get(key.toInt())
                Versioned(if (deleted(state)) null else "$key@${versionOf(state)}", versionOf(state).toLong())
            }
        }.engineSpec(),
        store,
        CONTEXT
    )

    @Operation
    fun get(@Param(name = "key") key: Int): Int = engine[key.toString()]?.substringAfter('@')?.toInt() ?: ABSENT

    @Operation
    fun putIfNewer(@Param(name = "key") key: Int) {
        val version = versionOf(source.updateAndGet(key) { (versionOf(it) + 1) * 2 })
        engine.putIfNewer(key.toString(), "$key@$version", version.toLong())
    }

    @Operation
    fun deleteIfNewer(@Param(name = "key") key: Int) {
        val version = versionOf(source.updateAndGet(key) { (versionOf(it) + 1) * 2 + 1 })
        engine.invalidateIfNewer(key.toString(), version.toLong())
    }

    @Operation
    fun evict(@Param(name = "key") key: Int) = engine.evict(key.toString())

    @Operation
    fun clearLocal() = engine.clearLocal()

    @Validate
    fun nothingLeft() = LincheckSupport.requireNoLease(store, KEYS)

    @Test
    fun modelCheck() = options(VersionedWriter::class.java)
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(2)
        .actorsPerThread(2)
        .check(this::class)

    /**
     * 定向场景：
     * - 读取的 loader 读到写入之前的快照，写入方的 `putIfNewer` 在它写回之前完成：旧版本的写回被拒绝，也不进入 L2；
     * - 两个写入方以相反的顺序写 L1：L1 留下最大的版本；
     * - `evict` 之后并发的读取与写入：读取的写回不低于 `E` 的版本。
     */
    @Test
    fun targetedInterleavings() = options(VersionedWriter::class.java)
        .iterations(0)
        .invocationsPerIteration(TARGETED_INVOCATIONS)
        .addCustomScenario {
            parallel {
                thread { actor(VersionedCacheLincheckTest::get, 0) }
                thread {
                    actor(VersionedCacheLincheckTest::putIfNewer, 0)
                    actor(VersionedCacheLincheckTest::get, 0)
                }
            }
        }
        .addCustomScenario {
            parallel {
                thread { actor(VersionedCacheLincheckTest::putIfNewer, 0) }
                thread { actor(VersionedCacheLincheckTest::deleteIfNewer, 0) }
            }
            post { actor(VersionedCacheLincheckTest::get, 0) }
        }
        .addCustomScenario {
            initial {
                actor(VersionedCacheLincheckTest::putIfNewer, 0)
                actor(VersionedCacheLincheckTest::get, 0)
            }
            parallel {
                thread {
                    actor(VersionedCacheLincheckTest::evict, 0)
                    actor(VersionedCacheLincheckTest::get, 0)
                }
                thread { actor(VersionedCacheLincheckTest::putIfNewer, 0) }
            }
        }
        .check(this::class)

    /**
     * 顺序规格：带版本的写入方。读取是当前提交的版本（已删除时 [ABSENT]）；`evict` 与 `clearLocal` 没有可见的效果。
     */
    class VersionedWriter {
        private val versions = IntArray(KEYS)
        private val deleted = BooleanArray(KEYS)

        fun get(key: Int): Int = if (deleted[key]) ABSENT else versions[key]

        fun putIfNewer(key: Int) {
            versions[key]++
            deleted[key] = false
        }

        fun deleteIfNewer(key: Int) {
            versions[key]++
            deleted[key] = true
        }

        @Suppress("UNUSED_PARAMETER", "UnusedParameter")
        fun evict(key: Int) = Unit

        fun clearLocal() = Unit
    }

    private companion object {
        const val KEYS = 2
        const val ABSENT = -1
        val ITERATIONS = LincheckSupport.budget(full = 5, ci = 1)
        val INVOCATIONS = LincheckSupport.budget(full = 300, ci = 100)
        val TARGETED_INVOCATIONS = LincheckSupport.budget(full = 500, ci = 120)

        fun versionOf(state: Int): Int = state / 2

        fun deleted(state: Int): Boolean = state % 2 == 1
    }
}
