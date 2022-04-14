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

package me.ahoo.cache.distributed.mock;

import me.ahoo.cache.eventbus.GuavaInvalidateEventBus;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Mock Distributed Caching Test .
 *
 * @author ahoo wang
 */
class MockDistributedCacheTest {
    private final MockDistributedCache<String> distributedCaching = new MockDistributedCache<>(new GuavaInvalidateEventBus(""));
    
    @Test
    void get() {
        final String CACHE_KEY = "get";
        final String CACHE_VALUE = "get";
        distributedCaching.set(CACHE_KEY, CACHE_VALUE);
        Assertions.assertEquals(CACHE_VALUE, distributedCaching.get(CACHE_KEY));
    }
    
    @Test
    void evict() {
        distributedCaching.evict("evict");
    }
    
}
