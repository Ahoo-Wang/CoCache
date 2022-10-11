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
import org.slf4j.LoggerFactory

/**
 * Guava impl Invalidate EventBus .
 *
 * @author ahoo wang
 */
class GuavaInvalidateEventBus @JvmOverloads constructor(
    override val clientId: String,
    private val eventBus: EventBus = EventBus(
        clientId
    )
) : InvalidateEventBus {
    private val noloop = true

    override fun publish(event: InvalidateEvent) {
        if (noloop && clientId == event.publisherId) {
            if (log.isDebugEnabled) {
                log.debug("publish - clientId:[{}] - Ignore your own posts. key:[{}].", clientId, event.key)
            }
            return
        }
        if (log.isDebugEnabled) {
            log.debug("publish - clientId:[{}] - event:[{}].", clientId, event)
        }
        eventBus.post(event)
    }

    override fun register(subscriber: InvalidateSubscriber) {
        eventBus.register(subscriber)
    }

    override fun unregister(subscriber: InvalidateSubscriber) {
        eventBus.unregister(subscriber)
    }

    companion object {
        private val log = LoggerFactory.getLogger(GuavaInvalidateEventBus::class.java)
    }
}
