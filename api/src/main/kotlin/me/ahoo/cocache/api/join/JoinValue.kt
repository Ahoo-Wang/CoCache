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

package me.ahoo.cocache.api.join

/**
 * [JoinCache] 的组合结果。
 *
 * @property firstValue 主值
 * @property joinKey 从主值提取的关联 key；`null` 表示该主值没有关联
 * @property secondValue 关联值；没有关联 key 或关联缓存中不存在时为 `null`
 */
data class JoinValue<V1 : Any, K2 : Any, V2 : Any>(
    val firstValue: V1,
    val joinKey: K2?,
    val secondValue: V2?,
)
