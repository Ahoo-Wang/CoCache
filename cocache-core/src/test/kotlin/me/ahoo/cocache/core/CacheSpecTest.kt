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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class CacheSpecTest {
    @Test
    fun builderDefaults() {
        val spec = CacheSpec.builder<Int, String>("user").codec(StringCodec).build()
        spec.name.assert().isEqualTo("user")
        spec.namespace.assert().isEqualTo("user")
        spec.keyPrefix.assert().isEqualTo(CacheSpec.DEFAULT_KEY_PREFIX)
        spec.keyEncoder.encode(42).assert().isEqualTo("42")
        spec.loader.assert().isNull()
        spec.ttlOf.assert().isNull()
        spec.keyFilter.assert().isNull()
        spec.policy.assert().isSameAs(CachePolicy.DEFAULT)
        spec.toString().assert().contains("name='user'")
    }

    @Test
    fun builderSetsEveryField() {
        val loader = CacheLoader<String, String> { it }
        val ttlOf = TtlFunction<String, String> { _, _ -> Duration.ofSeconds(1) }
        val filter = KeyFilter { false }
        val spec = CacheSpec.builder<String, String>("user")
            .namespace("ns")
            .keyPrefix("app:")
            .keyEncoder { "id-$it" }
            .codec(StringCodec)
            .loader(loader)
            .ttlOf(ttlOf)
            .keyFilter(filter)
            .policy { ttl = Duration.ofMinutes(1) }
            .build()
        spec.namespace.assert().isEqualTo("ns")
        spec.keyPrefix.assert().isEqualTo("app:")
        spec.keyEncoder.encode("1").assert().isEqualTo("id-1")
        spec.loader.assert().isSameAs(loader)
        spec.ttlOf.assert().isSameAs(ttlOf)
        spec.keyFilter.assert().isSameAs(filter)
        spec.policy.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        CacheSpec.builder<String, String>(
            "x"
        ).codec(StringCodec).policy(CachePolicy(ttl = null)).build().policy.ttl.assert().isNull()
    }

    @Test
    fun dslBuildsSpecs() {
        val spec = cacheSpec<String, String>("user") { codec = StringCodec }
        spec.name.assert().isEqualTo("user")
        val setSpec = setCacheSpec<String, String>("index") {
            elementCodec = StringCodec
            namespace = "idx"
            keyPrefix = "p:"
            keyEncoder = KeyEncoder { it.lowercase() }
        }
        setSpec.namespace.assert().isEqualTo("idx")
        setSpec.keyPrefix.assert().isEqualTo("p:")
        setSpec.keyEncoder.encode("A").assert().isEqualTo("a")
        setSpec.policy.ttl.assert().isNull()
        setSpec.toString().assert().contains("index")
    }

    @Test
    fun setSpecBuilderMethods() {
        val spec = SetCacheSpec.builder<String, String>("index")
            .namespace("ns")
            .keyPrefix("p:")
            .keyEncoder { it }
            .elementCodec(StringCodec)
            .policy { ttl = Duration.ofMinutes(1) }
            .build()
        spec.policy.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        SetCacheSpec.builder<String, String>(
            "i"
        ).elementCodec(StringCodec).policy(CachePolicy.DEFAULT).build().policy.assert().isSameAs(CachePolicy.DEFAULT)
    }

    @Test
    fun rejectsMissingCodecAndBlankNames() {
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { CacheSpec.builder<String, String>("user").build() }
        fails { CacheSpec.builder<String, String>(" ").codec(StringCodec).build() }
        fails { CacheSpec.builder<String, String>("user").namespace(" ").codec(StringCodec).build() }
        fails { SetCacheSpec.builder<String, String>("index").build() }
    }

    @Test
    fun toBuilderCopiesEveryField() {
        val loader = CacheLoader<String, String> { it }
        val spec = CacheSpec.builder<String, String>("user")
            .namespace("ns").keyPrefix("p:").codec(StringCodec).loader(loader)
            .ttlOf { _, _ -> Duration.ofSeconds(1) }.keyFilter { false }
            .policy { ttl = Duration.ofMinutes(1) }.build()
        val copy = spec.toBuilder().build()
        copy.namespace.assert().isEqualTo("ns")
        copy.keyPrefix.assert().isEqualTo("p:")
        copy.codec.assert().isSameAs(StringCodec)
        copy.loader.assert().isSameAs(loader)
        copy.ttlOf.assert().isSameAs(spec.ttlOf)
        copy.keyFilter.assert().isSameAs(spec.keyFilter)
        copy.policy.assert().isSameAs(spec.policy)
        copy.keyEncoder.assert().isSameAs(spec.keyEncoder)

        val setSpec = setCacheSpec<String, String>("index") {
            elementCodec = StringCodec
            namespace = "idx"
        }
        val setCopy = setSpec.toBuilder().build()
        setCopy.namespace.assert().isEqualTo("idx")
        setCopy.elementCodec.assert().isSameAs(StringCodec)
        setCopy.policy.assert().isSameAs(setSpec.policy)
        setCopy.keyEncoder.assert().isSameAs(setSpec.keyEncoder)
        setCopy.keyPrefix.assert().isEqualTo(setSpec.keyPrefix)
    }

    @Test
    fun updatePolicyStartsFromTheCurrentPolicy() {
        val setSpec = SetCacheSpec.builder<String, String>("index")
            .elementCodec(StringCodec)
            .updatePolicy { it.localMaxAge(Duration.ofMinutes(1)) }
            .build()
        // 集合缓存保持永不过期，只改了限龄
        setSpec.policy.ttl.assert().isNull()
        setSpec.policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        val spec = CacheSpec.builder<String, String>("user")
            .codec(StringCodec)
            .updatePolicy { it.ttl(Duration.ofMinutes(2)) }
            .build()
        spec.policy.ttl.assert().isEqualTo(Duration.ofMinutes(2))
    }
}
