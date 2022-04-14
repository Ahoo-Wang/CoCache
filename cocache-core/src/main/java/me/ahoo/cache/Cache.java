package me.ahoo.cache;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caching api.
 *
 * @author ahoo wang
 */
public interface Cache<K, V> extends CacheGetter<K, V> {
    
    /**
     * Get the real cache value.
     *
     * @param key cache key
     * @return real cache value
     */
    @Nullable
    @Override
    default V get(@Nonnull K key) {
        CacheValue<V> cacheValue = getCache(key);
        if (null == cacheValue) {
            return null;
        }
        if (cacheValue.isMissingGuard()) {
            return null;
        }
        if (cacheValue.isExpired()) {
            evict(key);
            return null;
        }
        return cacheValue.getValue();
    }
    
    @Nullable
    CacheValue<V> getCache(K key);
    
    /**
     * get cache expire at time.
     *
     * @param key cache key
     * @return when return {@literal null}:cache not exist.
     */
    @Nullable
    default Long getExpireAt(K key) {
        CacheValue<V> cacheValue = getCache(key);
        if (null != cacheValue) {
            return cacheValue.getTtlAt();
        }
        return null;
    }
    
    default void set(@Nonnull K key, long ttlAt, @Nonnull V value) {
        setCache(key, CacheValue.of(value, ttlAt));
    }
    
    default void set(@Nonnull K key, @Nonnull V value) {
        CacheValue<V> cacheValue = CacheValue.missingGuardValue().equals(value)
            ? CacheValue.missingGuard() : CacheValue.forever(value);
        setCache(key, cacheValue);
    }
    
    void setCache(@Nonnull K key, @Nonnull CacheValue<V> value);
    
    /**
     * evict cache.
     *
     * @param key cache key
     */
    void evict(@Nonnull K key);
    
}
