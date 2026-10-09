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

package me.ahoo.cache.annotation

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.proxy.MockCacheWithKeyExpression
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class CoCacheMetadataParserTest {

    @Test
    fun parse() {
        val metadata = coCacheMetadata<MockCache>()
        metadata.proxyInterface.assert().isEqualTo(MockCache::class)
        metadata.cacheName.assert().isEqualTo(metadata.proxyInterface.simpleName)
        metadata.keyPrefix.assert().isEqualTo("")
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(CoCacheMetadataParserTest::class)
    }

    @Test
    fun parseWithKeyExp() {
        val metadata = coCacheMetadata<MockCacheWithKeyExpression>()
        metadata.proxyInterface.assert().isEqualTo(MockCacheWithKeyExpression::class)
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(String::class)
        metadata.keyExpression.assert().isEqualTo("#{#root}")
        metadata.keyPrefix.assert().isEqualTo("prefix:")
    }

    @Test
    fun parseIfNotInterface() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            coCacheMetadata<NotInterface>()
        }
    }

    @Test
    fun parseGenericValueCache() {
        val metadata = coCacheMetadata<MockGenericValueCache>()
        metadata.proxyInterface.assert().isEqualTo(MockGenericValueCache::class)
        metadata.cacheName.assert().isEqualTo(metadata.proxyInterface.simpleName)
        metadata.keyPrefix.assert().isEqualTo("")
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(List::class)
        metadata.valueType.arguments[0].type!!.classifier.assert().isEqualTo(CoCacheMetadataParserTest::class)
    }

    @Test
    fun typeArgumentMustBeConcrete() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            typeOf<List<*>>().typeArgument(0)
        }
    }

    @Test
    fun parseTtlPolicy() {
        val metadata = coCacheMetadata<MockTtlCache>()
        metadata.cacheName.assert().isEqualTo("ttl-cache")
        metadata.ttlPolicy.assert().isEqualTo(TtlPolicy(ttl = 100, ttlAmplitude = 5, missingTtl = 7))
    }

    @Test
    fun parseInheritedCache() {
        val metadata = coCacheMetadata<MockInheritedCache>()
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(CoCacheMetadataParserTest::class)
    }

    @CoCache
    interface MockCache : Cache<String, CoCacheMetadataParserTest>

    @CoCache
    interface MockGenericValueCache : Cache<String, List<CoCacheMetadataParserTest>>

    @CoCache(name = "ttl-cache", ttl = 100, ttlAmplitude = 5, missingTtl = 7)
    interface MockTtlCache : Cache<String, CoCacheMetadataParserTest>

    @CoCache
    interface MockInheritedCache : MockCache
    abstract class NotInterface : Cache<String, CoCacheMetadataParserTest>
}
