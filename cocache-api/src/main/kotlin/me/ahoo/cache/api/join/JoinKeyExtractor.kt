package me.ahoo.cache.api.join

/**
 * Join Key Extractor.
 *
 * @author ahoo wang
 */
fun interface JoinKeyExtractor<V1, K2> {
    fun extract(firstValue: V1): K2
}
