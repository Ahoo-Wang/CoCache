package me.ahoo.cache.join

import me.ahoo.cache.CacheGetter

/**
 * Join Caching.
 *
 * @author ahoo wang
 */
interface JoinCache<K1, V1, K2, V2> : CacheGetter<K1, JoinValue<V1, K2, V2>?> {
    val extractJoinKey: ExtractJoinKey<V1, K2>
}
