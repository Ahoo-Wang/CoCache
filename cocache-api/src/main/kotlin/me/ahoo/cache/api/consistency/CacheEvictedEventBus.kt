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

/**
 * 失效事件通道 SPI。
 *
 * 实现约定：
 * - [publish] 尽力而为（至多一次），失败不得阻断调用方。
 * - 订阅建立（含断线重连后的重新订阅）时必须回调 [CacheEvictedSubscriber.onReset]。
 *
 * @author ahoo wang
 */
interface CacheEvictedEventBus {
    fun publish(event: CacheEvictedEvent)

    fun register(subscriber: CacheEvictedSubscriber)

    fun unregister(subscriber: CacheEvictedSubscriber)
}
