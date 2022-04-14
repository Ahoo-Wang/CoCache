package me.ahoo.cache.join;

import me.ahoo.cache.CacheGetter;

/**
 * Join Caching.
 *
 * @author ahoo wang
 */
public interface JoinCache<K1, V1, K2, V2> extends CacheGetter<K1, JoinValue<V1, K2, V2>> {
    
    ExtractJoinKey<V1, K2> getExtractJoinKey();
}
