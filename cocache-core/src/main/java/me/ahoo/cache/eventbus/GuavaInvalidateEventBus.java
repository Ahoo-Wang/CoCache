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

package me.ahoo.cache.eventbus;

import com.google.common.eventbus.EventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Guava impl Invalidate EventBus .
 *
 * @author ahoo wang
 */
@Slf4j
public class GuavaInvalidateEventBus implements InvalidateEventBus {
    
    private final EventBus eventBus;
    private final boolean noloop = true;
    private final String clientId;
    
    public GuavaInvalidateEventBus(String clientId) {
        this(clientId, new EventBus(clientId));
    }
    
    public GuavaInvalidateEventBus(String clientId, EventBus eventBus) {
        this.clientId = clientId;
        this.eventBus = eventBus;
    }
    
    @Override
    public void publish(InvalidateEvent event) {
        if (noloop && Objects.equals(getClientId(), event.getPublisherId())) {
            if (log.isDebugEnabled()) {
                log.debug("publish - clientId:[{}] - Ignore your own posts. key:[{}].", getClientId(), event.getKey());
            }
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("publish - clientId:[{}] - event:[{}].", getClientId(), event);
        }
        eventBus.post(event);
    }
    
    @Override
    public void register(InvalidateSubscriber subscriber) {
        eventBus.register(subscriber);
    }
    
    @Override
    public void unregister(InvalidateSubscriber subscriber) {
        eventBus.unregister(subscriber);
    }
    
    @Override
    public String getClientId() {
        return clientId;
    }
    
}
