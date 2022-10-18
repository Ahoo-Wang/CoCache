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

package me.ahoo.cache;

import me.ahoo.cache.util.CacheSecondClock;

/**
 * TtlAt .
 *
 * @author ahoo wang
 */
public interface TtlAt {
    /**
     * 9223372036854775807L.
     */
    long FOREVER = Long.MAX_VALUE;
    
    /**
     * get time to live({@link java.time.temporal.ChronoUnit#SECONDS}).
     *
     * @return time to live
     */
    long getTtlAt();
    
    static boolean isForever(long ttlAt) {
        return FOREVER == ttlAt;
    }
    
    default boolean isForever() {
        return isForever(getTtlAt());
    }
    
    default boolean isExpired() {
        if (isForever()) {
            return false;
        }
        return CacheSecondClock.INSTANCE.currentTime() > getTtlAt();
    }
    
}