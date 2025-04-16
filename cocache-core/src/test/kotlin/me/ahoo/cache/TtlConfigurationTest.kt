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

package me.ahoo.cache

import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.client.MapClientSideCache
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test

class TtlConfigurationTest {
    @Test
    fun testGetFirstTtlConfiguration() {
        val ttl = 1L
        val ttlAmplitude = 2L
        val ttlConfiguration = getFirstTtlConfiguration(MapClientSideCache<Any>(ttl = ttl, ttlAmplitude = ttlAmplitude))
        assertThat(ttlConfiguration.ttl, equalTo(ttl))
        assertThat(ttlConfiguration.ttlAmplitude, equalTo(ttlAmplitude))
    }

    @Test
    fun testGetFirstTtlConfigurationIfEmpty() {
        val ttlConfiguration = getFirstTtlConfiguration()
        assertThat(ttlConfiguration.ttl, equalTo(CoCache.DEFAULT_TTL))
        assertThat(ttlConfiguration.ttlAmplitude, equalTo(CoCache.DEFAULT_TTL_AMPLITUDE))
    }
}
