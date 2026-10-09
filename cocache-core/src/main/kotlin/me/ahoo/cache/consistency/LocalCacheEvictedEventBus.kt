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

package me.ahoo.cache.consistency

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 进程内同步失效事件总线：按 cacheName 路由，适用于测试与同进程多实例场景。
 *
 * 注册即视为订阅建立，回调 [CacheEvictedSubscriber.onReset]。
 */
class LocalCacheEvictedEventBus : CacheEvictedEventBus {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val subscribers = ConcurrentHashMap<String, MutableSet<CacheEvictedSubscriber>>()

    override fun publish(event: CacheEvictedEvent) {
        subscribers[event.cacheName]?.forEach { subscriber ->
            runCatching {
                subscriber.onEvicted(event)
            }.onFailure {
                log.warn(it) { "Subscriber[$subscriber] failed to handle event:[$event]." }
            }
        }
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        val added = subscribers.computeIfAbsent(subscriber.cacheName) { CopyOnWriteArraySet() }.add(subscriber)
        if (added) {
            subscriber.onReset()
        }
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        subscribers[subscriber.cacheName]?.remove(subscriber)
    }
}
