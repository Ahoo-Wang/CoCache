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

import me.ahoo.cocache.redis.RedisBackendOptions

/**
 * 定制 starter 的 `cocacheBackend`（Redis）使用的 [RedisBackendOptions]（Spring Boot 的定制器约定）：在 `cocache.redis.*` 与
 * Spring Boot 的 SSL bundle 都已应用之后、创建后端之前，按 Spring 的顺序（`@Order` / `Ordered`）依次调用，每个定制器收到前一个的结果。
 * 选项是不可变的，定制器返回新的选项（`copy`）。
 *
 * 只想改后端的个别选项（如共享应用的 Lettuce `ClientResources`）时用它，而不是替换 `cocacheBackend` bean。
 *
 * ```kotlin
 * @Bean
 * fun sharedResources(resources: ClientResources): RedisBackendOptionsCustomizer =
 *     RedisBackendOptionsCustomizer { it.copy(clientResources = resources) }
 * ```
 */
fun interface RedisBackendOptionsCustomizer {
    fun customize(options: RedisBackendOptions): RedisBackendOptions
}
