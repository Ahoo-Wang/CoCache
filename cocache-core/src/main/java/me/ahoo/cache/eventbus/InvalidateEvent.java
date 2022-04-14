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

import me.ahoo.cache.distributed.DistributedClientId;

import com.google.common.base.Objects;

/**
 * Invalidate Event .
 *
 * @author ahoo wang
 */
public class InvalidateEvent {
    
    public static final String TYPE = "invalid";
    
    private final String key;
    private final String publisherId;
    
    protected InvalidateEvent(String key, String publisherId) {
        this.key = key;
        this.publisherId = publisherId;
    }
    
    /**
     * get cache key.
     *
     * @return cache key
     */
    public String getKey() {
        return key;
    }
    
    /**
     * get publisher client ID {@link DistributedClientId#getClientId()}.
     *
     * @return publisherId
     */
    public String getPublisherId() {
        return publisherId;
    }
    
    public static InvalidateEvent of(String key, String publisherId) {
        return new InvalidateEvent(key, publisherId);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InvalidateEvent)) {
            return false;
        }
        InvalidateEvent that = (InvalidateEvent) o;
        return Objects.equal(getKey(), that.getKey()) && Objects.equal(getPublisherId(), that.getPublisherId());
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(getKey(), getPublisherId());
    }
    
    @Override
    public String toString() {
        return "InvalidateEvent{"
            + "key='" + key + '\''
            + ", publisherId='" + publisherId + '\''
            + '}';
    }
}
