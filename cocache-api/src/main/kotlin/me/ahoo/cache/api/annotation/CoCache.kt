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

/**
 * 声明缓存接口，由框架生成代理实现。
 *
 * - 缓存接口必须是接口，且继承 [me.ahoo.cache.api.Cache]。
 * - TTL 单位均为秒。
 *
 * @see me.ahoo.cache.api.Cache
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class CoCache(
    /**
     * 缓存名，默认取接口简单类名。
     */
    val name: String = "",
    /**
     * 存储层 key 前缀，默认 `cocache:{name}:`；支持 Spring 占位符。
     */
    val keyPrefix: String = "",
    /**
     * key 的 SpEL 模板表达式（如 `#{id}`），为空时使用 `key.toString()`。
     */
    val keyExpression: String = "",
    /**
     * 命中值的 TTL。有限的默认值保证任何不一致都能在 TTL 内自愈；
     * 设为 [me.ahoo.cache.api.TtlAt.FOREVER] 时一致性完全依赖显式失效。
     */
    val ttl: Long = DEFAULT_TTL,
    /**
     * 命中值 TTL 的随机抖动幅度，防止批量同时到期（缓存雪崩）。
     */
    val ttlAmplitude: Long = DEFAULT_TTL_AMPLITUDE,
    /**
     * 负缓存（数据源不存在）的 TTL，应远小于 [ttl]：数据新建后最多在该时长内仍被视为不存在。
     */
    val missingTtl: Long = DEFAULT_MISSING_TTL,
) {
    companion object {
        const val COCACHE = "cocache"

        const val DEFAULT_TTL: Long = 3600
        const val DEFAULT_TTL_AMPLITUDE: Long = 60
        const val DEFAULT_MISSING_TTL: Long = 60
    }
}
