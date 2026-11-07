/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cocache.core.policy;

import me.ahoo.cocache.core.Locks;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 调用方的写法：策略字段经 {@link PolicyFields} 的常量，哨兵经 {@code Ttl.FOREVER}、{@code RefreshWindow.OFF}、
 * {@code Concurrency.UNLIMITED} 与类型化的 setter，不再写 {@code PolicyField.TTL.INSTANCE} 或 {@code null}。
 */
class JavaPolicyFacadeTest {
    @Test
    void typedSentinelsWriteTheirFields() {
        CachePolicy.Builder builder = DefaultPolicies.CACHE.toBuilder()
            .ttl(Ttl.FOREVER)
            .refreshWindow(RefreshWindow.OFF)
            .maxConcurrent(Concurrency.UNLIMITED)
            .tombstoneTtl(Ttl.of(Duration.ofMinutes(3)));
        CachePolicy policy = builder.build();
        assertNull(policy.getTtl());
        assertNull(policy.getRefreshWindow());
        assertNull(policy.getLoad().getMaxConcurrent());
        assertEquals(Duration.ofMinutes(3), policy.getTombstoneTtl());
        assertTrue(builder.getWritten().contains(PolicyFields.TTL));
        assertTrue(builder.getWritten().contains(PolicyFields.REFRESH_WINDOW));
        assertTrue(builder.getWritten().contains(PolicyFields.LOAD_MAX_CONCURRENT));
        assertTrue(builder.getWritten().contains(PolicyFields.TOMBSTONE_TTL));

        CachePolicy limited = DefaultPolicies.CACHE.toBuilder().maxConcurrent(new Concurrency.Max(4)).build();
        assertEquals(4, limited.getLoad().getMaxConcurrent());
    }

    @Test
    void fieldConstantsAreTheFields() {
        assertSame(PolicyField.TTL.INSTANCE, PolicyFields.TTL);
        assertSame(PolicyField.UNKNOWN_READ.INSTANCE, PolicyFields.UNKNOWN_READ);
        assertSame(Ttl.Forever.INSTANCE, Ttl.FOREVER);
        assertSame(RefreshWindow.Off.INSTANCE, RefreshWindow.OFF);
        assertSame(Concurrency.Unlimited.INSTANCE, Concurrency.UNLIMITED);

        Locks locks = Locks.builder("my-lib")
            .lock(PolicyFields.UNKNOWN_READ)
            .constrain(PolicyFields.TTL, ttl -> ttl.finiteOr(Duration.ofHours(1)))
            .build();
        assertTrue(locks.isLocked(PolicyFields.UNKNOWN_READ));
    }
}
