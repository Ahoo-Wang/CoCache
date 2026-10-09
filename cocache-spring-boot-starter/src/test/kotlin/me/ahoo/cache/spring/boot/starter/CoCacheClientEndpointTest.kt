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

package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.api.Cache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CoCacheClientEndpointTest : AbstractCoCacheEndpointTest() {
    private lateinit var endpoint: CoCacheClientEndpoint

    @BeforeEach
    override fun setup() {
        super.setup()
        endpoint = CoCacheClientEndpoint(cacheFactory)
    }

    @Test
    fun getSize() {
        endpoint.getSize(CACHE_NAME).assert().isEqualTo(0)
    }

    @Test
    fun getSizeWhenNotFound() {
        endpoint.getSize(NOT_FOUND).assert().isNull()
    }

    @Test
    fun get() {
        endpoint.get(CACHE_NAME, "key").assert().isNull()
    }

    @Test
    fun getWhenNotFound() {
        endpoint.get(NOT_FOUND, "key").assert().isNull()
    }

    @Test
    fun clear() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "clear-key"
        cache[key] = "value"
        endpoint.getSize(CACHE_NAME).assert().isOne()
        endpoint.clear(CACHE_NAME)
        endpoint.getSize(CACHE_NAME).assert().isZero()
    }

    @Test
    fun clearWhenNotFound() {
        endpoint.clear(NOT_FOUND)
    }
}
