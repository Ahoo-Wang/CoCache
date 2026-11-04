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

import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.engine.LincheckSupport.CLOCK
import me.ahoo.cocache.core.engine.LincheckSupport.CONTEXT
import me.ahoo.cocache.core.engine.LincheckSupport.NAME
import me.ahoo.cocache.core.engine.LincheckSupport.options
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.Validate
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 权威副本 `Store`（RFC 0005 §4.1，M5a）在单节点上的交错：L1 是唯一副本，读取不申请 lease（C11），读到的值经失效戳保护进入 L2。
 * 写入（`put`、`delete`）与读取并发时可线性化为一个按 key 的寄存器（[Register]）；存储记录每次读取是否申请了 lease，
 * 执行结束时校验一次也没有（[noLease]）。
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
@Param(name = "value", gen = IntGen::class, conf = "1:3")
class StoreLincheckTest {
    private val leases = AtomicInteger()
    private val store = LeaseCountingStore(InMemoryBackend(CLOCK).store(Keyspace("cocache:", NAME)), leases)
    private val engine = StoreEngine(
        storeSpec<String, String>(NAME) { codec = StringCodec }.engineSpec(),
        store,
        CONTEXT
    )

    @Operation
    fun get(@Param(name = "key") key: Int): Int = engine[key.toString()]?.toInt() ?: ABSENT

    @Operation
    fun put(
        @Param(
            name = "key"
        ) key: Int,
        @Param(name = "value") value: Int
    ) = engine.put(key.toString(), value.toString())

    @Operation
    fun delete(@Param(name = "key") key: Int) = engine.delete(key.toString())

    @Validate
    fun noLease() = check(leases.get() == 0) { "a Store read requested ${leases.get()} lease(s)" }

    @Test
    fun modelCheck() = options(Register::class.java)
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(2)
        .actorsPerThread(2)
        .check(this::class)

    /**
     * 定向场景：写入方修改 L1 期间并发的读取从旧的 L1 读到值、在写入返回之后才填 L2（写入方的两次递增），`put` 与 `delete` 各一次。
     */
    @Test
    fun targetedInterleavings() = options(Register::class.java)
        .iterations(0)
        .invocationsPerIteration(TARGETED_INVOCATIONS)
        .addCustomScenario {
            initial { actor(StoreLincheckTest::put, 0, 1) }
            parallel {
                thread {
                    actor(StoreLincheckTest::put, 0, 2)
                    actor(StoreLincheckTest::get, 0)
                }
                thread { actor(StoreLincheckTest::get, 0) }
            }
        }
        .addCustomScenario {
            initial {
                actor(StoreLincheckTest::put, 0, 1)
                actor(StoreLincheckTest::get, 0)
            }
            parallel {
                thread {
                    actor(StoreLincheckTest::delete, 0)
                    actor(StoreLincheckTest::get, 0)
                }
                thread { actor(StoreLincheckTest::get, 0) }
            }
        }
        .check(this::class)

    /**
     * 顺序规格：按 key 的寄存器。
     */
    class Register {
        private val values = IntArray(KEYS) { ABSENT }

        fun get(key: Int): Int = values[key]

        fun put(key: Int, value: Int) {
            values[key] = value
        }

        fun delete(key: Int) {
            values[key] = ABSENT
        }
    }

    /**
     * 记录申请 lease 的读取。
     */
    private class LeaseCountingStore(delegate: L1Store, private val leases: AtomicInteger) : ForwardingL1Store(
        delegate
    ) {
        override suspend fun read(
            key: String,
            lease: LeaseRequest?,
            refresh: RefreshRequest?,
            timeout: Duration?
        ): L1Read {
            lease?.let { leases.incrementAndGet() }
            return delegate.read(key, lease, refresh, timeout)
        }

        override suspend fun readAll(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>,
            timeout: Duration?
        ): Map<String, L1Read> {
            this.leases.addAndGet(leases.size)
            return delegate.readAll(keys, leases, timeout)
        }
    }

    private companion object {
        const val KEYS = 2
        const val ABSENT = -1
        const val ITERATIONS = 5
        const val INVOCATIONS = 300
        const val TARGETED_INVOCATIONS = 500
    }
}
