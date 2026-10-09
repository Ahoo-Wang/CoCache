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

package me.ahoo.cache.api.filter

/**
 * 存储层 key 过滤器（如布隆过滤器）：确定不存在的 key 直接判为负缓存，不访问 L1 与数据源。
 *
 * @author ahoo wang
 */
fun interface KeyFilter {
    fun notExist(key: String): Boolean

    companion object {
        @JvmField
        val NO_OP: KeyFilter = KeyFilter { false }
    }
}
