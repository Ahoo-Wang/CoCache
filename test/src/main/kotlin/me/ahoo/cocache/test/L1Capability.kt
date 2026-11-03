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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import java.util.EnumSet
import java.util.UUID

/**
 * L1 存储的可选能力（RFC 0005 §4.3），供 TCK 的声明使用：每个规范的 `expectedCapabilities` 声明后端提供哪些能力（没有默认值，
 * 忘记声明的后端编译失败），规范断言存储提供的能力与声明的完全相同（多出来的能力也算失败），需要某个能力的用例只在它没有声明时跳过。
 *
 * 枚举只是 TCK 的声明，不进入 SPI：能力只由存储的访问器表达（[L1Store.conditional]、[L1Store.versioned]、[L1Store.sets]），
 * 否则同一个事实又有两处表达。
 */
enum class L1Capability {
    /**
     * 条件读写：[L1Store.conditional] 非 `null`（`getIfChanged`、`putIfMatch`）。
     */
    CONDITIONAL,

    /**
     * 按版本有序的写入：[L1Store.versioned] 非 `null`（带版本的缓存，[VersionedSpec]）。
     */
    VERSIONED,

    /**
     * 集合成员的增删：[L1Store.sets] 非 `null`（集合缓存，[SetEngineSpec]）。
     */
    SETS;

    /**
     * [store] 上这个能力的访问器的一次调用结果。
     */
    internal fun surfaceOf(store: L1Store): Any? = when (this) {
        CONDITIONAL -> store.conditional()
        VERSIONED -> store.versioned()
        SETS -> store.sets()
    }

    /**
     * 访问器的名字（失败消息用）。
     */
    internal val accessor: String
        get() = name.lowercase()

    companion object {
        /**
         * [store] 提供的能力：访问器返回非 `null` 的那些。
         */
        @JvmStatic
        fun of(store: L1Store): Set<L1Capability> =
            entries.filterTo(EnumSet.noneOf(L1Capability::class.java)) { it.surfaceOf(store) != null }
    }
}

/**
 * 各规范共用的能力检查。
 */
internal object CapabilityDeclaration {
    /**
     * 每个访问器在一个存储上调用的次数：每次都必须返回同一个对象（或总是 `null`）。
     */
    private const val CALLS = 3

    /**
     * 断言 [backend] 的存储提供的能力与 [declared] 完全相同，且每个访问器稳定（同一个对象，或总是 `null`，见 [L1Store] 的类注释）。
     * 检查两个新的命名空间：能力属于后端，不随命名空间变化（`runtime.create` 按每个命名空间的存储检查）。
     */
    fun verify(backend: CoherenceBackend, declared: Set<L1Capability>) {
        repeat(2) {
            val namespace = "tck-capabilities-" + UUID.randomUUID().toString().substring(0, 8)
            val store = backend.store(namespace)
            val provided = L1Capability.of(store)
            provided.assert().withFailMessage {
                "${store.javaClass.name} on namespace[$namespace] provides the L1 capabilities $provided, " +
                    "but expectedCapabilities declares $declared."
            }.isEqualTo(declared)
            L1Capability.entries.forEach { capability ->
                val first = capability.surfaceOf(store)
                repeat(CALLS - 1) {
                    capability.surfaceOf(store).assert().withFailMessage {
                        "${store.javaClass.name}.${capability.accessor}() must return the same object on every call."
                    }.isSameAs(first)
                }
            }
        }
    }

    /**
     * 用例需要的能力：[declared] 缺少其中任何一个时跳过这个用例（只在没有声明时跳过；声明了而存储不提供时由 [verify] 失败，
     * 用例本身也会失败）。
     */
    fun assume(declared: Set<L1Capability>, required: Array<out L1Capability>) {
        val missing = required.filterNot { it in declared }
        Assumptions.assumeTrue(missing.isEmpty()) { "expectedCapabilities does not declare $missing." }
    }
}
