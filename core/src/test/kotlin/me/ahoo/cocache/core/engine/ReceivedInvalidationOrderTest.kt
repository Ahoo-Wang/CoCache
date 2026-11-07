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

import com.github.benmanes.caffeine.cache.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ExternalInvalidator
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * 收到的失效先生效、后计数：观察到 `invalidationsReceived` 增加时，L2 副本已经淘汰。TCK 与各测试据此等待别的节点的失效生效
 * （先等计数、再断言读到的值），先计数的话，断言可能落在计数与淘汰之间（`RedisLayoutTest` 在 Valkey 上偶发失败）。
 *
 * 用 L2 的按 key 锁让淘汰停住（Caffeine 的 `compute` 在执行期间持有这个 key 的锁），判据是淘汰停住期间计数还没有增加，而不是比较耗时。
 */
class ReceivedInvalidationOrderTest {
    @Test
    fun aReceivedInvalidationIsCountedOnlyAfterTheCopyIsEvicted() {
        InMemoryBackend().use { memory ->
            CacheRuntime.builder().backend(memory).build().use { runtime ->
                val name = "ordered"
                val cache = runtime.create(
                    cacheSpec<String, String>(name) {
                        codec = StringCodec
                        loader = CacheLoader { "v-$it" }
                    }
                )
                cache["k"].assert().isEqualTo("v-k")
                val metrics = requireNotNull(runtime.metrics(name))
                val local = localOf(cache)
                val holding = CountDownLatch(1)
                val release = CountDownLatch(1)
                val holder = fork("l2-lock-holder") {
                    local.asMap().compute("k") { _, entry ->
                        holding.countDown()
                        release.awaitOrFail("L2 lock released")
                        entry
                    }
                }
                holding.awaitOrFail("holding the L2 lock")
                // 内存后端在发布的线程上同步投递失效帧：淘汰停在这个线程上
                val broadcaster = fork("broadcaster") {
                    ExternalInvalidator(memory).use { it.broadcast(name, "k") }
                }
                awaitCondition("the eviction waits on the L2 lock") {
                    broadcaster.thread.state == Thread.State.BLOCKED &&
                        broadcaster.thread.stackTrace.any { it.className.startsWith("com.github.benmanes.caffeine") }
                }
                metrics.invalidationsReceived.assert().withFailMessage {
                    "the invalidation was counted before the copy was evicted"
                }.isEqualTo(0L)
                release.countDown()
                holder.join()
                broadcaster.join()
                metrics.invalidationsReceived.assert().isEqualTo(1L)
                local.getIfPresent("k").assert().isNull()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun localOf(cache: Any): Cache<String, Any> {
        val field = AbstractEngine::class.java.getDeclaredField("local")
        field.isAccessible = true
        return field.get(cache) as Cache<String, Any>
    }
}
