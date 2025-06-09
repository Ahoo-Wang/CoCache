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

package me.ahoo.cache.spring.join

import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.join.ExpJoinKeyExtractor
import me.ahoo.cache.join.JoinKeyExtractorFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import kotlin.reflect.jvm.javaType

class SpringJoinKeyExtractorFactory(private val beanFactory: BeanFactory) : JoinKeyExtractorFactory {
    companion object {
        const val JOIN_KEY_EXTRACTOR_SUFFIX = ".JoinKeyExtractor"
    }

    override fun <V1, K2> create(cacheMetadata: JoinCacheMetadata): JoinKeyExtractor<V1, K2> {
        if (cacheMetadata.joinKeyExpression.isNotBlank()) {
            @Suppress("UNCHECKED_CAST")
            return ExpJoinKeyExtractor<V1>(cacheMetadata.joinKeyExpression) as JoinKeyExtractor<V1, K2>
        }
        val beanName = cacheMetadata.cacheName + JOIN_KEY_EXTRACTOR_SUFFIX
        if (beanFactory.containsBean(beanName)) {
            @Suppress("UNCHECKED_CAST")
            return beanFactory.getBean(beanName) as JoinKeyExtractor<V1, K2>
        }

        val beanType = ResolvableType.forClassWithGenerics(
            JoinKeyExtractor::class.java,
            ResolvableType.forType(cacheMetadata.firstValueType.javaType),
            ResolvableType.forType(cacheMetadata.joinKeyType.javaType)
        )

        val provider = beanFactory.getBeanProvider<JoinKeyExtractor<V1, K2>>(beanType)
        return requireNotNull(provider.getIfUnique()) {
            "[${this.javaClass.simpleName}] Not found for $cacheMetadata."
        }
    }
}
