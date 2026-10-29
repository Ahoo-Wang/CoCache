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

import me.ahoo.cocache.test.CacheAccess
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * TCK 的等待工具必须能发现违反 A8 的实现（保存了 CompletionException 的 future）与同步抛出。
 */
class CacheAccessAwaitTest {
    @Test
    fun detectsWrappedExceptionsAndSynchronousThrows() {
        val failure = IllegalStateException("boom")
        runCatching { CacheAccess.await { CompletableFuture.failedFuture<String>(failure) } }
            .exceptionOrNull().assert().isSameAs(failure)
        runCatching { CacheAccess.await { CompletableFuture.failedFuture<String>(CompletionException(failure)) } }
            .exceptionOrNull().assert().isInstanceOf(AssertionError::class.java)
        runCatching { CacheAccess.await<String> { throw failure } }
            .exceptionOrNull().assert().isInstanceOf(AssertionError::class.java)
        CacheAccess.await { CompletableFuture.completedFuture("v") }.assert().isEqualTo("v")
        CacheAccess.SYNC.toString().assert().isEqualTo("CacheAccess.SYNC")
        CacheAccess.ASYNC_WITH_SYNC_SOURCE.toString().assert().isEqualTo("CacheAccess.ASYNC_WITH_SYNC_SOURCE")
    }
}
