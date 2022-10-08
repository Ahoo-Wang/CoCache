package me.ahoo.cache.join

/**
 * Join Value.
 *
 * @author ahoo wang
 */
data class JoinValue<V1, K2, V2>(val firstValue: V1, val joinKey: K2, val joinValue: V2?)
