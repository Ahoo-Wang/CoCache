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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.join.JoinCache
import org.springframework.context.annotation.Import
import kotlin.reflect.KClass

/**
 * 注册接口式声明的缓存：`@EnableCoCache(caches = [UserCache::class])`（RFC 0003 §4.12）。
 *
 * 每个接口注册为一个 bean：bean 名为缓存名（默认接口简单类名），类型为接口本身。
 * 运行时与后端由 CoCache 自动配置提供（`cocache.enabled`，默认启用）。
 *
 * [joinCaches] 列出组合视图接口（继承 `JoinCache`，用 `@JoinCacheable` 声明），bean 名为组合视图名（默认接口简单类名），
 * 按名称引用 [caches] 中的接口式缓存或 spec 声明的缓存。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(AnnotatedCacheRegistrar::class, JoinCacheRegistrar::class)
annotation class EnableCoCache(
    val caches: Array<KClass<out Cache<*, *>>> = [],
    val joinCaches: Array<KClass<out JoinCache<*, *, *, *>>> = [],
)
