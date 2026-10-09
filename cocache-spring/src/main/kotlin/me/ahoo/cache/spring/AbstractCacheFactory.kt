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

package me.ahoo.cache.spring

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.annotation.CoCacheMetadata
import org.springframework.beans.factory.BeanFactory

/**
 * 解析缓存组件：优先使用名为 `{cacheName}{suffix}` 的 bean；未定义时由 [resolveByType] 或 [fallback] 提供。
 *
 * 有状态组件（L2、L1、key 转换器）只按名称解析，避免多个缓存因类型相同而共享同一实例。
 */
abstract class AbstractCacheFactory(protected val beanFactory: BeanFactory) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    protected abstract val suffix: String

    /**
     * 按类型解析（仅限可安全共享的无状态组件），默认不启用。
     */
    protected open fun resolveByType(cacheMetadata: CoCacheMetadata): Any? = null

    protected abstract fun fallback(cacheMetadata: CoCacheMetadata): Any

    protected fun resolve(cacheMetadata: CoCacheMetadata): Any {
        val beanName = cacheMetadata.cacheName + suffix
        if (beanFactory.containsBean(beanName)) {
            return beanFactory.getBean(beanName)
        }
        resolveByType(cacheMetadata)?.let {
            return it
        }
        log.debug { "[${this.javaClass.simpleName}] Bean[$beanName] not found, fallback." }
        return fallback(cacheMetadata)
    }
}
