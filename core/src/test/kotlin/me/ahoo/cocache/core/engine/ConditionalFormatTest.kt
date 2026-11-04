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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.ForwardingConditionalL1Store
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 条件读取只认本节点的格式（RFC 0005 §4.7）：后端报告的 `Unchanged` 或 `Changed` 的格式低于或高于本节点时，
 * `getIfChanged` 按读不懂的内容抛出，`cause` 为带该格式的 [ForeignFormatException]。
 */
class ConditionalFormatTest {
    /**
     * 把条件读取结果的格式改为 [format] 的后端（其余操作原样转发给内存后端）。
     */
    private class ReformattingBackend(private val delegate: InMemoryBackend) : CoherenceBackend {
        @Volatile
        var reported: Int? = null

        override val atomicBroadcast: Boolean get() = delegate.atomicBroadcast
        override val format: Int get() = delegate.format
        override val channel: InvalidationChannel get() = delegate.channel

        override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(delegate.store(keyspace)) {
            private val conditional = object : ForwardingConditionalL1Store(requireNotNull(delegate.conditional())) {
                override suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead {
                    val read = delegate.readIfChanged(key, known, timeout)
                    val reported = reported ?: return read
                    return when (read) {
                        is L1ConditionalRead.Unchanged -> L1ConditionalRead.Unchanged(reported)
                        is L1ConditionalRead.Changed -> L1ConditionalRead.Changed(read.envelope, reported)
                        else -> read
                    }
                }
            }

            override fun conditional(): ConditionalL1Store = conditional
        }

        override fun close() = delegate.close()
    }

    @Test
    fun conditionalReadsAcceptOnlyThisNodesFormat() {
        val backend = ReformattingBackend(InMemoryBackend())
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            val cache = runtime.create(storeSpec<String, String>("conditional-format") { codec = StringCodec })
            cache.put("k", "v")
            val digest = requireNotNull(cache.getIfChanged("k", null) as? ConditionalGet.Present).digest
            cache.getIfChanged("k", digest).assert().isEqualTo(ConditionalGet.Unchanged)
            for (format in listOf(0, InMemoryBackend.FORMAT - 1, InMemoryBackend.FORMAT + 1)) {
                backend.reported = format
                for (known in listOf(digest, null)) {
                    val error = runCatching { cache.getIfChanged("k", known) }.exceptionOrNull()
                    error.assert().isInstanceOf(CacheReadException::class.java)
                    requireNotNull(error?.cause as? ForeignFormatException).format.assert().isEqualTo(format)
                }
            }
        }
    }
}
