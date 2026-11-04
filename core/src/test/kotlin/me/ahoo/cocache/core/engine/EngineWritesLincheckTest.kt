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

import me.ahoo.cocache.core.engine.EngineOperationsLincheckTest.Companion.CANCELLATION_INVOCATIONS
import me.ahoo.cocache.core.engine.EngineOperationsLincheckTest.Companion.OPERATION_INVOCATIONS
import me.ahoo.cocache.core.engine.EngineOperationsLincheckTest.Companion.targeted
import org.junit.jupiter.api.Test

/**
 * [EngineOperationsLincheckTest] 的对象与顺序规格上，写入与调用方取消的定向场景（单独一个类：`lincheck` 任务按类并行）。
 */
class EngineWritesLincheckTest {
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
        .check(EngineOperationsLincheckTest::class)

    /**
     * 定向场景：调用方取消。
     * - 被中断的同步调用方作为 leader：交还 lease，合并到它的读取自行回源；
     * - 被取消的挂起调用方作为跟随者：只结束它自己的等待，之后的写入与读取照常（读己之写）。
     * 之后读到最新的版本，L1 上没有留下 lease（[EngineOperationsLincheckTest.nothingLeft]）。
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
        .check(EngineOperationsLincheckTest::class)
}
