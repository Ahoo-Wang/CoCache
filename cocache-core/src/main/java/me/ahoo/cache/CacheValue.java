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

import com.google.common.base.Objects;

import java.util.Map;
import java.util.Set;

/**
 * Cache Value .
 *
 * @author ahoo wang
 */
public class CacheValue<V> implements TtlAt {
    
    /**
     * missing guard.
     */
    public static final MissingGuard MISSING_GUARD_VALUE = MissingGuard.INSTANCE;
    public static final String MISSING_GUARD_STRING_VALUE = MissingGuard.STRING_VALUE;
    public static final CacheValue<?> MISSING_GUARD_CACHE_VALUE = forever(MISSING_GUARD_VALUE);
    private final V value;
    private final long ttlAt;
    
    protected CacheValue(V value, long ttlAt) {
        this.value = value;
        this.ttlAt = ttlAt;
    }
    
    public V getValue() {
        return value;
    }
    
    /**
     * get time to live({@link java.time.temporal.ChronoUnit#SECONDS}).
     *
     * @return time to live (second)
     */
    @Override
    public long getTtlAt() {
        return ttlAt;
    }
    
    public static <V> CacheValue<V> forever(V value) {
        return of(value, FOREVER);
    }
    
    public static <V> CacheValue<V> of(V value, long ttlAt) {
        return new CacheValue<>(value, ttlAt);
    }
    
    public boolean isMissingGuard() {
        return missingGuardValue().equals(getValue());
    }
    
    public static boolean isMissingGuard(String value) {
        return MISSING_GUARD_STRING_VALUE.equals(value);
    }
    
    public static boolean isMissingGuard(Set<String> value) {
        if (value.isEmpty()) {
            return false;
        }
        return MISSING_GUARD_STRING_VALUE.equals(value.stream().findFirst().get());
    }
    
    public static boolean isMissingGuard(Map<String, String> value) {
        if (value.isEmpty()) {
            return false;
        }
        return value.containsKey(MISSING_GUARD_STRING_VALUE);
    }
    
    @SuppressWarnings("unchecked")
    public static <V extends CacheValue<?>> V missingGuard() {
        return (V) MISSING_GUARD_CACHE_VALUE;
    }
    
    @SuppressWarnings("unchecked")
    public static <V> V missingGuardValue() {
        return (V) MISSING_GUARD_VALUE;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CacheValue)) {
            return false;
        }
        CacheValue<?> that = (CacheValue<?>) o;
        return getTtlAt() == that.getTtlAt() && Objects.equal(getValue(), that.getValue());
    }
    
    
    @Override
    public int hashCode() {
        return Objects.hashCode(getValue(), getTtlAt());
    }
    
    public static class MissingGuard {
        
        public static final MissingGuard INSTANCE = new MissingGuard();
        public static final String STRING_VALUE = "_nil_";
        
        private MissingGuard() {
        }
        
        @Override
        public int hashCode() {
            return MissingGuard.class.hashCode();
        }
        
        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }
        
        @Override
        public String toString() {
            return STRING_VALUE;
        }
        
    }
}
