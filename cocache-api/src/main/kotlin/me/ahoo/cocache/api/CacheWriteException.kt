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
 * 显式写入（put、元素增删、clear，或配置为同步失败的失效）在 L1 上失败。
 *
 * 失败时 L1 可能已经生效（如响应超时）；这些操作都是幂等的，调用方可以安全重试。
 */
class CacheWriteException(
    val cacheName: String,
    val operation: String,
    cause: Throwable
) : RuntimeException("Cache[$cacheName] - $operation failed on the remote store.", cause)
