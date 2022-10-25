package me.ahoo.cache.join

/**
 * Extract Join Key.
 *
 * @author ahoo wang
 */
fun interface ExtractJoinKey<V1, K2> {
    fun extract(firstValue: V1): K2
}
