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

package me.ahoo.cache.api.consistency

import me.ahoo.cache.api.NamedCache

/**
 * 失效事件订阅者，只接收 [cacheName] 频道的事件。
 *
 * @author ahoo wang
 */
interface CacheEvictedSubscriber : NamedCache {
    fun onEvicted(cacheEvictedEvent: CacheEvictedEvent)

    /**
     * 失效通道（重新）建立订阅：此前的失效事件可能已丢失，订阅者必须放弃所有本地副本。
     *
     * 这是 L2 陈旧度有界的关键：事件通道至多一次投递，断线期间的事件只能通过重置兜底。
     */
    fun onReset()
}
