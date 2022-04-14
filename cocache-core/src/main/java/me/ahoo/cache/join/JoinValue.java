package me.ahoo.cache.join;

import javax.annotation.Nullable;

/**
 * Join Value.
 *
 * @author ahoo wang
 */
public class JoinValue<V1, K2, V2> {
    
    private final V1 firstValue;
    private final K2 joinKey;
    @Nullable
    private final V2 joinValue;
    
    public JoinValue(V1 firstValue, K2 joinKey, @Nullable V2 joinValue) {
        this.firstValue = firstValue;
        this.joinKey = joinKey;
        this.joinValue = joinValue;
    }
    
    public V1 getFirstValue() {
        return firstValue;
    }
    
    public K2 getJoinKey() {
        return joinKey;
    }
    
    @Nullable
    public V2 getJoinValue() {
        return joinValue;
    }
}
