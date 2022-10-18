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

package me.ahoo.cache.source;

import me.ahoo.cache.CacheSource;
import me.ahoo.cache.CacheValue;

/**
 * No Op Cache Source .
 *
 * @author ahoo wang
 */
public class NoOpCacheSource<K, V> implements CacheSource<K, V> {
    @SuppressWarnings("rawtypes")
    private static final NoOpCacheSource INSTANCE = new NoOpCacheSource();
    
    @Override
    public CacheValue<V> load(K key) {
        return null;
    }
    
    @SuppressWarnings("unchecked")
    public static <K, V> CacheSource<K, V> noOp() {
        return (CacheSource<K, V>) INSTANCE;
    }
}