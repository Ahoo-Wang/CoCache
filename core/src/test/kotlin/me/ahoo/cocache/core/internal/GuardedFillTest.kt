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

package me.ahoo.cocache.core.internal

import com.github.benmanes.caffeine.cache.Caffeine
import me.ahoo.cocache.core.engine.LocalEntry
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction

/**
 * 戳保护的 L2 填充（[fillGuarded]，RFC 0005 §4.5、附录 A.D4）。写入方以“递增戳（[bumped]）→ 淘汰这个 key”模拟，
 * 与引擎的写入方顺序相同；L2 是有界的 Caffeine（与引擎相同）。
 */
class GuardedFillTest {
    /**
     * 按身份比较的副本（与 `LocalEntry` 相同）。
     */
    private class Copy(val value: String)

    private fun l2(): ConcurrentMap<String, Copy> = Caffeine.newBuilder().maximumSize(100).build<String, Copy>().asMap()

    private val bumped = AtomicBoolean()

    private fun stillValid(): Boolean = !bumped.get()

    /**
     * 记下写入方完成之后写入 [stale] 的次数：A.D4 的判据是写入方完成之后，旧值不再写进 L2。
     */
    private class Recording(private val delegate: ConcurrentMap<String, Copy>, private val stale: Copy) :
        ConcurrentMap<String, Copy> by delegate {
        @Volatile
        var writerDone = false
        val staleWritesAfterWriter = AtomicInteger()

        override fun put(key: String, value: Copy): Copy? = delegate.put(key, value).also { record(value) }

        override fun compute(key: String, remapping: BiFunction<in String, in Copy?, out Copy?>): Copy? =
            delegate.compute(key, remapping).also { record(it) }

        private fun record(written: Copy?) {
            if (writerDone && written === stale) {
                staleWritesAfterWriter.incrementAndGet()
            }
        }
    }

    /**
     * 对照用的锁外算法：先校验，再写入，复核失败时撤销（按 key）。
     */
    private fun unlockedFill(
        map: ConcurrentMap<String, Copy>,
        key: String,
        entry: Copy,
        valid: () -> Boolean
    ): Boolean {
        if (!valid()) {
            return false
        }
        map[key] = entry
        if (!valid()) {
            map.remove(key)
            return false
        }
        return true
    }

    /**
     * A.D4：预检查刚通过时整个写入方完成（递增戳、淘汰 L2）。锁外算法随后仍写入旧值，在复核撤销之前对读取可见：
     * 写入方已经返回，本节点随后的读取却读到写入之前的值。锁内校验时不再写入。
     */
    @Test
    fun aWriterCompletingRightAfterThePreCheckLeavesNoStaleWrite() {
        fun staleWritesAfterWriter(fill: (ConcurrentMap<String, Copy>, Copy, () -> Boolean) -> Boolean): Int {
            bumped.set(false)
            val stale = Copy("old")
            val map = Recording(l2(), stale)
            var calls = 0
            fill(map, stale) {
                calls++
                if (calls == 1) {
                    fork("writer") {
                        bumped.set(true)
                        map.remove("k")
                    }.join()
                    map.writerDone = true
                    true
                } else {
                    stillValid()
                }
            }
            map["k"].assert().isNull()
            return map.staleWritesAfterWriter.get()
        }
        staleWritesAfterWriter { map, entry, valid -> unlockedFill(map, "k", entry, valid) }.assert().isEqualTo(1)
        staleWritesAfterWriter { map, entry, valid -> map.fillGuarded("k", entry) { valid() } }.assert().isEqualTo(0)
    }

    /**
     * 写入方在锁内校验通过之后递增戳：它随后的淘汰等这个 key 的锁，删掉这一份；复核同样撤销它。最终 L2 中没有它。
     */
    @Test
    fun aWriterBumpingDuringTheComputeEvictsTheFill() {
        val map = l2()
        val fill = Copy("v")
        val writerBumped = CountDownLatch(1)
        var writer: Concurrency.Forked<Copy?>? = null
        var calls = 0
        val kept = map.fillGuarded("k", fill) {
            calls++
            if (calls == 2) {
                // 锁内：校验已经通过；写入方现在递增戳，它的淘汰等这个 key 的锁
                writer = fork("writer") {
                    bumped.set(true)
                    writerBumped.countDown()
                    map.remove("k")
                }
                writerBumped.awaitOrFail("writer bumped")
                true
            } else {
                stillValid()
            }
        }
        requireNotNull(writer).join()
        kept.assert().isFalse()
        map["k"].assert().isNull()
    }

    /**
     * 重置（递增全部戳并清空 L2）看不到进行中的 `compute`：写后复核撤销这一份（C4）。
     */
    @Test
    fun aResetDuringTheComputeIsUndoneByTheRecheck() {
        val map = l2()
        val fill = Copy("v")
        val resetBumped = CountDownLatch(1)
        var reset: Concurrency.Forked<Unit>? = null
        var calls = 0
        val kept = map.fillGuarded("k", fill) {
            calls++
            if (calls == 2) {
                reset = fork("reset") {
                    bumped.set(true)
                    resetBumped.countDown()
                    map.clear()
                }
                resetBumped.awaitOrFail("reset bumped")
                true
            } else {
                stillValid()
            }
        }
        requireNotNull(reset).join()
        kept.assert().isFalse()
        map["k"].assert().isNull()
    }

    /**
     * 复核只撤销自己写入的那一份：其间别的填充写入的副本（内容相同也一样）保留。
     */
    @Test
    fun theRecheckRemovesOnlyItsOwnCopy() {
        val map = l2()
        val own = Copy("v")
        val newer = Copy("v")
        var calls = 0
        val kept = map.fillGuarded("k", own) {
            calls++
            if (calls == 3) {
                map["k"] = newer
                false
            } else {
                true
            }
        }
        kept.assert().isFalse()
        map["k"].assert().isSameAs(newer)
    }

    @Test
    fun aFailedPreCheckDoesNotTouchTheMap() {
        val map = Recording(l2(), Copy("unused"))
        map["k"] = Copy("current")
        var calls = 0
        val kept = map.fillGuarded("k", Copy("v")) {
            calls++
            false
        }
        kept.assert().isFalse()
        calls.assert().isEqualTo(1)
        map["k"]?.value.assert().isEqualTo("current")
    }

    @Test
    fun aValidFillIsKept() {
        val map = l2()
        val fill = Copy("v")
        map.fillGuarded("k", fill) { true }.assert().isTrue()
        map["k"].assert().isSameAs(fill)
    }

    /**
     * 复核按身份撤销的前提：L2 副本不按值比较。
     */
    @Test
    fun localEntriesCompareByIdentity() {
        val entry = LocalEntry("v", 1)
        entry.assert().isNotEqualTo(LocalEntry("v", 1))
        entry.assert().isEqualTo(entry)
    }
}
