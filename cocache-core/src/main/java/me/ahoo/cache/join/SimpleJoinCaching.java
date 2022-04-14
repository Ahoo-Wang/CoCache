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

package me.ahoo.cache.join;

import me.ahoo.cache.CacheGetter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Simple Join Caching .
 *
 * @author ahoo wang
 */
public class SimpleJoinCaching<K1, V1, K2, V2> implements JoinCache<K1, V1, K2, V2> {
    private final CacheGetter<K1, V1> firstCaching;
    private final CacheGetter<K2, V2> joinCaching;
    private final ExtractJoinKey<V1, K2> extractJoinKey;
    
    public SimpleJoinCaching(CacheGetter<K1, V1> firstCaching,
                             CacheGetter<K2, V2> joinCaching,
                             ExtractJoinKey<V1, K2> extractJoinKey) {
        this.firstCaching = firstCaching;
        this.joinCaching = joinCaching;
        this.extractJoinKey = extractJoinKey;
    }
    
    @Nullable
    @Override
    public JoinValue<V1, K2, V2> get(@Nonnull K1 key) {
        V1 firstValue = firstCaching.get(key);
        if (null == firstValue) {
            return null;
        }
        
        K2 joinKey = extractJoinKey.extract(firstValue);
        V2 secondValue = joinCaching.get(joinKey);
        
        return new JoinValue<>(firstValue, joinKey, secondValue);
    }
    
    @Override
    public ExtractJoinKey<V1, K2> getExtractJoinKey() {
        return extractJoinKey;
    }
    
}
