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

package me.ahoo.cocache.api

/**
 * 读取时 L1 失败，且缓存配置为严格读失败模式（`ReadFailureMode.THROW`，对应 5.x 的 `strictFailure = true`）。
 *
 * 默认模式下 L1 读失败会降级为直接回源，不会抛出本异常。
 */
class CacheReadException(
    val cacheName: String,
    val operation: String,
    cause: Throwable
) : RuntimeException("Cache[$cacheName] - $operation failed on the remote store.", cause)
