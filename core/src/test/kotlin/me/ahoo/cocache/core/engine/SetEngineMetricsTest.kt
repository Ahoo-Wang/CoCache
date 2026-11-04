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

import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * SetStore 的分层命中计数：同步与异步视图都把 L2 直接返回的读取计入 `localHits`。
 */
class SetEngineMetricsTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).recordLocalHits(true).build()
    private val target: SetStore<String, String> = runtime.create(setStoreSpec("index") { elementCodec = StringCodec })
    private val metrics = requireNotNull(runtime.metrics("index"))

    @AfterEach
    fun destroy() {
        runtime.close()
    }

    private fun assertLocalHits(index: SetStore<String, String>) {
        index.add("k", listOf("a"))
        index["k"].assert().containsExactly("a")
        metrics.localHits.assert().isZero()
        metrics.remoteHits.assert().isEqualTo(1)

        index["k"].assert().containsExactly("a")
        index.lookup("k").assert().isInstanceOf(Lookup.Present::class.java)
        index.getAll(listOf("k")).assert().containsKey("k")
        metrics.localHits.assert().isEqualTo(3)
        metrics.misses.assert().isZero()
    }

    @Test
    fun syncReadsServedByL2AreCounted() {
        assertLocalHits(CacheAccess.SYNC.setStore(target))
    }

    @Test
    fun asyncReadsServedByL2AreCounted() {
        assertLocalHits(CacheAccess.ASYNC.setStore(target))
    }
}
