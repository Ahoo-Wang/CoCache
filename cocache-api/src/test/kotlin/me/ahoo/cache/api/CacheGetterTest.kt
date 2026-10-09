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

package me.ahoo.cache.api

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * [CacheGetter] 默认方法契约：未命中、负缓存、已过期都不暴露值与到期时间。
 */
class CacheGetterTest {
    private class FixedGetter(private val cacheValue: CacheValue<String>?) : CacheGetter<String, String> {
        override fun getCache(key: String): CacheValue<String>? = cacheValue
    }

    @Test
    fun absentEntryHasNoValueOrTtl() {
        val getter = FixedGetter(null)
        getter["key"].assert().isNull()
        getter.getTtlAt("key").assert().isNull()
    }

    @Test
    fun presentEntryExposesValueAndTtl() {
        val cacheValue = CacheValue.of("value", TtlAt.at(60))
        val getter = FixedGetter(cacheValue)
        getter["key"].assert().isEqualTo("value")
        getter.getTtlAt("key").assert().isEqualTo(cacheValue.ttlAt)
    }

    @Test
    fun expiredEntryHasNoValueOrTtl() {
        val getter = FixedGetter(CacheValue.of("value", TtlAt.currentTime() - 1))
        getter["key"].assert().isNull()
        getter.getTtlAt("key").assert().isNull()
    }

    @Test
    fun missingEntryHasNoValueOrTtl() {
        val getter = FixedGetter(CacheValue.missing(TtlAt.at(60)))
        getter["key"].assert().isNull()
        getter.getTtlAt("key").assert().isNull()
    }
}
