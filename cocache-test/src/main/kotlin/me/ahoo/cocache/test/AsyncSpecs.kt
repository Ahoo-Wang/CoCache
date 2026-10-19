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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * 异步视图的集合缓存 TCK（RFC 0004 §10.1）：[SetEngineSpec] 的每个用例都经 `async()` 重跑。
 */
abstract class AsyncSetEngineSpec : SetEngineSpec() {
    override val access: CacheAccess = CacheAccess.ASYNC

    @Test
    fun unsupportedOperationsFailTheFutureInsteadOfThrowing() {
        val async = nodeA.create(setCacheSpec<String, String>(cacheName) { elementCodec = StringCodec }).async()
        async.invalidate(
            "k"
        ).handle { _, e -> e }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        async.invalidateAll(
            listOf("k")
        ).handle { _, e -> e }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        async.get("k", AsyncCacheLoader { error("unused") }).handle { _, e -> e }.join()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        async.get("k").join().assert().isEmpty()
        async.sync().assert().isSameAs(async.sync())
    }
}

/**
 * 异步视图的多节点 TCK（RFC 0004 §10.1）：[MultiNodeSpec] 的每个用例都经 `async()` 重跑。
 */
abstract class AsyncMultiNodeSpec : MultiNodeSpec() {
    override val access: CacheAccess = CacheAccess.ASYNC
}

/**
 * 异步视图的故障 TCK（RFC 0004 §10.1）：[FaultSpec] 的每个用例都经 `async()` 重跑。
 */
abstract class AsyncFaultSpec : FaultSpec() {
    override val access: CacheAccess = CacheAccess.ASYNC
}
