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

import me.ahoo.cache.api.annotation.CaffeineCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.CaffeineClientSideCache.Companion.toClientSideCache
import me.ahoo.cache.test.ClientSideCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

/**
 * CaffeineClientSideCache Test .
 *
 * @author ahoo wang
 */
internal class CaffeineCacheTest : ClientSideCacheSpec<String>() {

    override fun createCache(): ClientSideCache<String> {
        return CaffeineClientSideCache()
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @Test
    fun defaultCacheConvert() {
        val cache = CaffeineCache().toClientSideCache<String>()
        cache.assert().isNotNull()
    }
}
