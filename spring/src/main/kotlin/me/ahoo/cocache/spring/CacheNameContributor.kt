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

package me.ahoo.cocache.spring

/**
 * 声明可能创建的缓存名：在运行时（启动之后、或延迟初始化的 bean 中）经 `CoCacheFactory` 创建缓存的库发布这样一个 bean，
 * 应用为这些缓存写下的 `cocache.caches.[name]` 不会因“未声明的缓存”让启动失败。
 *
 * 它是库与 starter 之间的契约，放在不依赖 Spring Boot 的 `cocache-spring` 中：库只需依赖它（与 `cocache-core`），
 * 不必依赖 starter；读取它的是 starter。
 *
 * starter 的名称校验在全部单例创建之后读取每个贡献者（延迟初始化的贡献者 bean 也在这时创建），按名称不区分大小写匹配；
 * 只放宽贡献的名称，其余未声明的名称照常按 `cocache.caches-validation` 处理。贡献一个名称不创建缓存，
 * 也不改变它的配置：缓存创建时照常叠加配置文件。
 *
 * ```kotlin
 * @Bean
 * fun tokenCacheNames(): CacheNameContributor = CacheNameContributor { listOf("cosec.token", "cosec.permission") }
 * ```
 */
fun interface CacheNameContributor {
    /**
     * 可能创建的缓存名（`Spec.name`）。
     */
    fun cacheNames(): Collection<String>
}
