package me.ahoo.cache.converter

/**
 * Key Converter.
 *
 * @author ahoo wang
 */
@FunctionalInterface
interface KeyConverter<K> {
    fun asKey(sourceKey: K): String
}
