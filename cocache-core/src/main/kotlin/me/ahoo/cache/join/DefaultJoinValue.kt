package me.ahoo.cache.join

import me.ahoo.cache.api.join.JoinValue

/**
 * Join Value.
 *
 * @author ahoo wang
 */
data class DefaultJoinValue<V1, K2, V2>(
    override val firstValue: V1,
    override val joinKey: K2,
    override val secondValue: V2?
) : JoinValue<V1, K2, V2>
