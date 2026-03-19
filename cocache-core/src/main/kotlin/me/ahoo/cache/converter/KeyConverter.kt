package me.ahoo.cache.converter

/**
 * Key Converter.
 *
 * @author ahoo wang
 */
fun interface KeyConverter<K> {
    fun toStringKey(sourceKey: K): String
}
