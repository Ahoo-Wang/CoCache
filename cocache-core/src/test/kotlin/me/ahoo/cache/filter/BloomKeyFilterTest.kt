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

package me.ahoo.cache.filter

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnel
import com.google.common.hash.PrimitiveSink
import me.ahoo.cosid.test.MockIdGenerator
import org.junit.jupiter.api.Assertions.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*

import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.util.UUID

internal class BloomKeyFilterTest {

    @Test
    fun notExist() {
        val bloomFilter = BloomFilter.create<String>(
            { from, into -> into.putString(from, Charset.defaultCharset()) },
            1000,
            0.01
        )
        val filter = BloomKeyFilter(bloomFilter)
        val id = UUID.randomUUID().toString()
        assertThat(filter.notExist(id), `is`(true))
        bloomFilter.put(id)
        assertThat(filter.notExist(id), `is`(false))
    }
}
