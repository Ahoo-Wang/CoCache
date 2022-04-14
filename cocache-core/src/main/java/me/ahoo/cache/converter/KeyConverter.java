package me.ahoo.cache.converter;

import me.ahoo.cache.KeyPrefix;

/**
 * Key Converter.
 *
 * @author ahoo wang
 */
@FunctionalInterface
public interface KeyConverter<K> extends KeyPrefix {
    
    String asString(K sourceKey);
}
