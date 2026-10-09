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

package me.ahoo.cache.api.annotation

import java.lang.annotation.Inherited
import java.util.concurrent.TimeUnit

/**
 * 配置 L2（Caffeine）客户端缓存。未标注时使用全部默认值。
 *
 * 条目按各自的 [me.ahoo.cache.api.CacheValue.ttlAt] 主动到期，无需配置写后过期。
 *
 * @see me.ahoo.cache.api.client.ClientSideCache
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class CaffeineCache(
    val initialCapacity: Int = UNSET,
    /**
     * 最大条目数。L2 必须有界，防止内存无限增长。
     */
    val maximumSize: Long = DEFAULT_MAXIMUM_SIZE,
    /**
     * 空闲淘汰时长（自最近一次访问起），`<= 0` 表示不启用。
     */
    val expireAfterAccess: Long = 0,
    val expireUnit: TimeUnit = TimeUnit.SECONDS,
) {
    companion object {
        const val UNSET: Int = -1
        const val DEFAULT_MAXIMUM_SIZE: Long = 10_000
    }
}
