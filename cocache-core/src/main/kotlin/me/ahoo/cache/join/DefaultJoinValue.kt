package me.ahoo.cache.join

import me.ahoo.cache.MissingGuard
import me.ahoo.cache.api.join.JoinValue

object DefaultJoinMissingGuard : JoinValue<String, String, String>, MissingGuard {
    override val firstValue: String
        get() = MissingGuard.STRING_VALUE
    override val joinKey: String
        get() = MissingGuard.STRING_VALUE
    override val secondValue: String?
        get() = MissingGuard.STRING_VALUE
}

/**
 * Join Value.
 *
 * @author ahoo wang
 */
data class DefaultJoinValue<V1, K2, V2>(
    override val firstValue: V1,
    override val joinKey: K2,
    override val secondValue: V2?
) : JoinValue<V1, K2, V2> {
    companion object {
        fun <V1, K2, V2> missingGuardValue(): JoinValue<V1, K2, V2> {
            @Suppress("UNCHECKED_CAST")
            return DefaultJoinMissingGuard as JoinValue<V1, K2, V2>
        }
    }
}
