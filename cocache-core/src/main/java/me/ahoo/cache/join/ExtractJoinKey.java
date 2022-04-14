package me.ahoo.cache.join;

import javax.annotation.Nonnull;

/**
 * Extract Join Key.
 *
 * @author ahoo wang
 */
@FunctionalInterface
public interface ExtractJoinKey<V1, K2> {
    @Nonnull
    K2 extract(@Nonnull V1 firstValue);
}
