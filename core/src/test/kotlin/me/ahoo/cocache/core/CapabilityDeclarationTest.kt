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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ForwardingConditionalL1Store
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.BackendProtocolSpec
import me.ahoo.cocache.test.L1Capability
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import java.time.Duration

/**
 * TCK 的能力声明 fail closed（RFC 0005 §4.3）：存储提供的能力必须与 `expectedCapabilities` 完全相同，多出来的、缺少的都失败；
 * 访问器必须稳定；需要能力的用例只在它没有声明时跳过。经规范自己的用例方法检验（规范的检查是 `cocache-test` 的内部实现）。
 */
class CapabilityDeclarationTest {
    private val all = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

    private class Protocol(
        private val tested: CoherenceBackend,
        override val expectedCapabilities: Set<L1Capability>
    ) : BackendProtocolSpec() {
        override fun createBackend(): CoherenceBackend = tested

        override fun elapse(duration: Duration) = Unit
    }

    /**
     * 在规范的生命周期内运行 [case]（`setup` 与 `destroy` 之间），返回它抛出的异常。
     */
    private fun failureOf(
        backend: CoherenceBackend,
        declared: Set<L1Capability>,
        case: Protocol.() -> Unit
    ): Throwable? {
        val spec = Protocol(backend, declared)
        spec.setup()
        try {
            return runCatching { spec.case() }.exceptionOrNull()
        } finally {
            spec.destroy()
        }
    }

    /**
     * 条件读写能力面不稳定的后端：第 n 次调用 `conditional()` 返回 [surface] 的结果。
     */
    private class Unstable(
        private val backend: CoherenceBackend,
        private val surface: (ConditionalL1Store?, Int) -> ConditionalL1Store?
    ) : CoherenceBackend by backend {
        override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(backend.store(keyspace)) {
            private var calls = 0

            override fun conditional(): ConditionalL1Store? = surface(delegate.conditional(), calls++)
        }
    }

    @Test
    fun exactDeclarationPasses() {
        failureOf(InMemoryBackend(), all) { storeProvidesExactlyTheDeclaredCapabilities() }.assert().isNull()
        failureOf(
            StrippedBackend(InMemoryBackend(), conditional = false),
            setOf(L1Capability.VERSIONED, L1Capability.SETS)
        ) {
            storeProvidesExactlyTheDeclaredCapabilities()
        }.assert().isNull()
    }

    @Test
    fun undeclaredCapabilityTheStoreProvidesFails() {
        val failure = failureOf(InMemoryBackend(), setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED)) {
            storeProvidesExactlyTheDeclaredCapabilities()
        }
        failure.assert().isInstanceOf(AssertionError::class.java)
        failure?.message.assert().contains("expectedCapabilities declares [CONDITIONAL, VERSIONED]")
    }

    @Test
    fun declaredCapabilityTheStoreLacksFails() {
        val failure = failureOf(StrippedBackend(InMemoryBackend(), sets = false), all) {
            storeProvidesExactlyTheDeclaredCapabilities()
        }
        failure.assert().isInstanceOf(AssertionError::class.java)
        failure?.message.assert().contains("provides the L1 capabilities [CONDITIONAL, VERSIONED]")
    }

    @Test
    fun accessorReturningANewObjectOnEveryCallFails() {
        val backend = Unstable(InMemoryBackend()) { surface, _ ->
            surface?.let { object : ForwardingConditionalL1Store(it) {} }
        }
        val failure = failureOf(backend, all) { storeProvidesExactlyTheDeclaredCapabilities() }
        failure.assert().isInstanceOf(AssertionError::class.java)
        failure?.message.assert().contains("conditional() must return the same object on every call")
    }

    @Test
    fun accessorThatIsOnlySometimesNullFails() {
        val backend = Unstable(InMemoryBackend()) { surface, call -> surface.takeIf { call % 2 == 0 } }
        val failure = failureOf(backend, all) { storeProvidesExactlyTheDeclaredCapabilities() }
        failure.assert().isInstanceOf(AssertionError::class.java)
        failure?.message.assert().contains("conditional() must return the same object on every call")
    }

    /**
     * 没有声明的能力：需要它的用例跳过（即使存储其实提供了它，声明检查另外失败）；声明了的照常运行。
     */
    @Test
    fun casesNeedingAnUndeclaredCapabilityAreSkipped() {
        failureOf(InMemoryBackend(), emptySet()) { membersAreAddedAtomicallyOverLeases() }
            .assert().isInstanceOf(TestAbortedException::class.java)
        failureOf(InMemoryBackend(), all) { membersAreAddedAtomicallyOverLeases() }.assert().isNull()
    }
}
