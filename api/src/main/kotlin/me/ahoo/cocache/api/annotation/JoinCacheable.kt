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

package me.ahoo.cocache.api.annotation

/**
 * 声明组合视图（`me.ahoo.cocache.api.join.JoinCache`）：
 * `@JoinCacheable(firstCacheName = "UserExtendInfoCache", joinCacheName = "UserCache", joinKeyExpression = "#{#root.userId}")`
 * 标注在继承 `JoinCache<K1, V1, K2, V2>` 的接口上，由 `@EnableCoCache(joinCaches = [...])` 注册为 bean。
 *
 * 关联 key 的提取方式三选一：[joinKeyExpression]、[joinKeyExtractor]，两者都为空时按泛型 `JoinKeyExtractor<V1, K2>` 唯一匹配。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class JoinCacheable(
    /**
     * 组合视图名（也是 bean 名），默认取接口简单类名。
     */
    val name: String = "",
    /**
     * 主缓存名（必填）：接口式声明的缓存名，或 spec 声明的缓存名 / bean 名。
     */
    val firstCacheName: String = "",
    /**
     * 关联缓存名（必填），规则同 [firstCacheName]。
     */
    val joinCacheName: String = "",
    /**
     * 关联 key 的 SpEL 模板表达式（如 `#{#root.userId}` 或 `#{userId}`，以主值为根对象），要求关联 key 类型为 `String`。
     * 结果为 `null` 表示没有关联。不能与 [joinKeyExtractor] 同时设置。
     */
    val joinKeyExpression: String = "",
    /**
     * `JoinKeyExtractor` 的 bean 名。不能与 [joinKeyExpression] 同时设置。
     */
    val joinKeyExtractor: String = "",
)
