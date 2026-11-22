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

package me.ahoo.cocache.core.memory

import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * 缺陷复现（#995）：L1 写入新值并在同一次原子写入中发布畸形的失效帧（非法 UTF-8 的 key、溢出 `Int` 的计数）时，帧曾被静默接受
 * （失效落到另一个 key，或被当作探测帧），频道不重置，读取继续命中 L2 的旧值。现在按读不懂的帧重置订阅者，读取确认 L1 得到新值。
 * 对照：合法的空帧（探测帧）照旧什么也不做。
 */
class MalformedInvalidationFrameTest {
    @ParameterizedTest
    @ValueSource(strings = ["badUtf8", "overflowCount", "probe"])
    fun aMalformedFrameResetsTheLocalCopies(kind: String) {
        InMemoryBackend().use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val spec = storeSpec<String, String>("store") { codec = StringCodec }
                val cache = runtime.create(spec)
                cache.put("k", "old")
                cache["k"].assert().isEqualTo("old")
                val storageKey = spec.keyspace.storageKey("k")
                val header = byteArrayOf(InvalidationFrame.VERSION) + NodeId.random().bytes
                val frame = when (kind) {
                    "badUtf8" -> header + byteArrayOf(1, 2, 'k'.code.toByte(), 0xFF.toByte())
                    "overflowCount" -> header + byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10)
                    else -> InvalidationFrame.encode(NodeId.random(), emptyList())
                }
                val bytes = StringCodec.encode("new")
                Suspending.await {
                    backend.store(spec.keyspace)
                        .put(
                            storageKey,
                            Envelope.Scalar(bytes, digest = Digest.ofPayload(bytes)),
                            Duration.ofMinutes(1),
                            frame
                        )
                }
                // 内存后端同步分发：写入返回时监听者已处理这条帧
                cache["k"].assert().isEqualTo(if (kind == "probe") "old" else "new")
            }
        }
    }
}
