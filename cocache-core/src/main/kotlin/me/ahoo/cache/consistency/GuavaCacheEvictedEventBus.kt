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

import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Guava impl cache Evicted EventBus .
 *
 * @author ahoo wang
 */
class GuavaCacheEvictedEventBus(
    private val eventBus: EventBus = EventBus()
) : CacheEvictedEventBus {
    companion object {
        private val log = LoggerFactory.getLogger(GuavaCacheEvictedEventBus::class.java)
    }

    private val subscribers = ConcurrentHashMap<CacheEvictedSubscriber, CacheEvictedSubscriberAdapter>()
    override fun publish(event: CacheEvictedEvent) {
        if (log.isDebugEnabled) {
            log.debug("Publish - event:[{}].", event)
        }
        eventBus.post(event)
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        if (log.isDebugEnabled) {
            log.debug("Register - subscriber:[{}].", subscriber)
        }
        subscribers.computeIfAbsent(subscriber) {
            CacheEvictedSubscriberAdapter(it).also { adapter ->
                eventBus.register(adapter)
            }
        }
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        if (log.isDebugEnabled) {
            log.debug("Unregister - subscriber:[{}].", subscriber)
        }
        subscribers.remove(subscriber)?.also {
            eventBus.unregister(it)
        }
    }
}

class CacheEvictedSubscriberAdapter(private val cacheEvictedSubscriber: CacheEvictedSubscriber) {
    @Subscribe
    fun subscribe(event: CacheEvictedEvent) {
        cacheEvictedSubscriber.onEvicted(event)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheEvictedSubscriberAdapter) return false

        if (cacheEvictedSubscriber != other.cacheEvictedSubscriber) return false

        return true
    }

    override fun hashCode(): Int {
        return cacheEvictedSubscriber.hashCode()
    }

    override fun toString(): String {
        return "CacheEvictedSubscriberAdapter(cacheEvictedSubscriber=$cacheEvictedSubscriber)"
    }
}
