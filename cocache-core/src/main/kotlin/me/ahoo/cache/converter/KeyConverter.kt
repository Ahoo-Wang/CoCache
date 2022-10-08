package me.ahoo.cache.converter

import me.ahoo.cache.KeyPrefix

/**
 * Key Converter.
 *
 * @author ahoo wang
 */
@FunctionalInterface
interface KeyConverter<K> : KeyPrefix {
    fun asKey(sourceKey: K): String
}
