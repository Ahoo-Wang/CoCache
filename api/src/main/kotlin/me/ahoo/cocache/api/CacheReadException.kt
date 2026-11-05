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
 * 读取没有答案（RFC 0005 §4.1）：
 * - 结果为 [Lookup.Unknown]（L1 故障或命令超时，`cause` 为原始异常）：权威副本与外部来源的缓存上不带 loader 的读取，
 *   或显式 `readFailure = THROW` 的带数据源的读取；
 * - 读到读不懂的内容，而没有数据源可以纠正（`cause` 为 `ForeignFormatException`）。
 *
 * 批量读取部分 key 没有答案时，先完成其余 key，再抛出本异常：[unknownKeys] 是没有答案的 key，[partialResult] 是其余存在的 key 的值。
 *
 * @param unknownKeys 没有答案的业务 key（单 key 读取为空）
 * @param partialResult 批量读取中已得到的、存在的 key 的值（单 key 读取为空）
 */
class CacheReadException @JvmOverloads constructor(
    val cacheName: String,
    val operation: String,
    cause: Throwable,
    val unknownKeys: Set<Any> = emptySet(),
    val partialResult: Map<Any, Any> = emptyMap(),
) : RuntimeException(messageOf(cacheName, operation, unknownKeys), cause) {
    private companion object {
        fun messageOf(cacheName: String, operation: String, unknownKeys: Set<Any>): String =
            if (unknownKeys.isEmpty()) {
                "Cache[$cacheName] - $operation failed on the remote store."
            } else {
                "Cache[$cacheName] - $operation failed on the remote store for ${unknownKeys.size} key(s)."
            }
    }
}
