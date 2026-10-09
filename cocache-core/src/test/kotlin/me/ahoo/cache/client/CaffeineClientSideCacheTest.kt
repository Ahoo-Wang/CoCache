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

package me.ahoo.cache.client

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CaffeineCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.CaffeineClientSideCache.Companion.toClientSideCache
import me.ahoo.cache.test.ClientSideCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*

internal class CaffeineClientSideCacheTest : ClientSideCacheSpec<String>() {

    override fun createCacheStore(): ClientSideCache<String> = CaffeineClientSideCache.build()

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @Test
    fun defaultAnnotationConvert() {
        CaffeineCache().toClientSideCache<String>().assert().isNotNull()
    }

    @Test
    fun maximumSizeMustBePositive() {
        runCatching { CaffeineClientSideCache.build<String>(maximumSize = 0) }.isFailure.assert().isTrue()
    }

    @Test
    fun expireAfterAccessIsSupported() {
        val cache = CaffeineClientSideCache.build<String>(expireAfterAccess = Duration.ofMinutes(1))
        cache.setCache("key", CacheValue.forever("value"))
        cache.getCache("key")?.value.assert().isEqualTo("value")
    }

    @Test
    fun customizedAnnotationConvert() {
        CaffeineCache(initialCapacity = 16, maximumSize = 100, expireAfterAccess = 30).toClientSideCache<String>()
            .assert().isNotNull()
    }

    @Test
    fun boundedByMaximumSize() {
        val cache = CaffeineClientSideCache.build<String>(maximumSize = 1)
        repeat(100) {
            cache.setCache("key-$it", CacheValue.forever("value"))
        }
        // Caffeine 异步执行淘汰，轮询等待收敛
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (cache.size > 1 && System.nanoTime() < deadline) {
            cache.getCache("key-0")
            Thread.sleep(10)
        }
        cache.size.assert().isLessThanOrEqualTo(1)
    }
}
