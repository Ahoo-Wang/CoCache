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

import me.ahoo.cocache.core.CacheRuntime

/**
 * 定制 starter 的 `cocacheRuntime`（Spring Boot 的定制器约定）：在 starter 接好后端、时钟、观测器、执行器、本地命中计数与关闭超时
 * 之后、`build()` 之前，按 Spring 的顺序（`@Order` / `Ordered`）依次调用。定制器设置的值覆盖 starter 的接线。
 *
 * 只想改运行时的个别设置时用它，而不是替换 `cocacheRuntime` bean：替换之后 starter 的接线全部不再生效
 * （时钟、`LoadObserver` / `FillObserver`、`cocache.executor` / `cocache.refresh-executor`、`cocache.metrics.local-hits`、
 * 由 `cocache.redis.command-timeout` 推出的关闭超时），定制器也不再被调用。
 *
 * ```kotlin
 * @Bean
 * fun retryCustomizer(): CacheRuntimeBuilderCustomizer = CacheRuntimeBuilderCustomizer { it.retryPolicy(RetryPolicy(maxAttempts = 10)) }
 * ```
 */
fun interface CacheRuntimeBuilderCustomizer {
    fun customize(builder: CacheRuntime.Builder)
}
