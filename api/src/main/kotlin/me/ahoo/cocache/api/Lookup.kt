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

import java.time.Instant

/**
 * [Cache.lookup] 的结果。
 */
sealed interface Lookup<out V> {
    /**
     * 返回的这份副本的到期时间（命中 L2 时是 L2 副本的到期时间），精度为缓存时钟的刻度；`null` 表示永不过期。
     */
    val expiresAt: Instant?

    /**
     * 命中值。
     */
    data class Present<out V>(val value: V, override val expiresAt: Instant?) : Lookup<V>

    /**
     * 确认不存在（负缓存）。
     */
    data class Absent(override val expiresAt: Instant?) : Lookup<Nothing>
}
