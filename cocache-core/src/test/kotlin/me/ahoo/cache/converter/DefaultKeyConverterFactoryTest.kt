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

import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.proxy.MockCache
import me.ahoo.cache.proxy.MockCacheWithKeyExpression
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class DefaultKeyConverterFactoryTest {
    @Test
    fun defaultPrefix() {
        val converter = DefaultKeyConverterFactory.INSTANCE.create<String>(coCacheMetadata<MockCache>())
        converter.toStringKey("id").assert().isEqualTo("cocache:MockCache:id")
    }

    @Test
    fun expressionWithPlaceholders() {
        val factory = DefaultKeyConverterFactory { it.replace("prefix", "resolved") }
        val converter = factory.create<String>(coCacheMetadata<MockCacheWithKeyExpression>())
        converter.toStringKey("id").assert().isEqualTo("resolved:id")
    }
}
