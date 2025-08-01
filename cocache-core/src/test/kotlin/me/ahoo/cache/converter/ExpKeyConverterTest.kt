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
package me.ahoo.cache.converter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * ExpKeyConverterTest .
 *
 * @author ahoo wang
 */
internal class ExpKeyConverterTest {
    @Test
    fun toStringKey() {
        val prefix = "prefix:"
        val expKeyConverter = ExpKeyConverter<String>(prefix, "#{#root}")
        Assertions.assertEquals(prefix, expKeyConverter.keyPrefix)
        val actual = expKeyConverter.toStringKey("asString")
        Assertions.assertEquals(prefix + "asString", actual)
    }

    @Test
    fun toStringKeyIfObject() {
        val prefix = "prefix:"
        val expKeyConverter = ExpKeyConverter<BrandNameIndexKey>(prefix, "#{tenantId}:#{name}")
        Assertions.assertEquals(prefix, expKeyConverter.keyPrefix)
        val brandNameIndexKey = BrandNameIndexKey("tenantId", "name")
        val actual = expKeyConverter.toStringKey(brandNameIndexKey)
        Assertions.assertEquals(prefix + "tenantId:name", actual)
    }

    @Test
    fun toStringKeyIfObjectWithoutPrefix() {
        val expKeyConverter = ExpKeyConverter<BrandNameIndexKey>("", "#{tenantId}:#{name}")
        expKeyConverter.toString().assert().isEqualTo("ExpKeyConverter(keyPrefix='', expression=#{tenantId}:#{name})")
    }
}

data class BrandNameIndexKey(val tenantId: String, val name: String)
