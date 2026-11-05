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

package me.ahoo.cocache.api.spi;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Java 实现者只实现返回 {@link CompletableFuture} 的方法：没有 {@code Continuation} 参数，也没有要覆盖的挂起方法；
 * 经 {@link FutureL1Store#adapt(FutureL1Store)} 得到 {@link L1Store}。
 */
class JavaFutureL1StoreTest {
    private static final byte[] FRAME = InvalidationFrame.encode(NodeId.random(), List.of("k"));
    private static final Envelope.Scalar VALUE = new Envelope.Scalar(new byte[] {1});

    @Test
    void operationsRunThroughTheAdapter() {
        L1Store l1 = FutureL1Store.adapt(new MapStore());

        Suspending.await(c -> l1.put("k", VALUE, null, FRAME, c));
        L1Read hit = new L1Read.Hit(VALUE, 1, null, false);
        L1Read miss = new L1Read.Miss(false);
        assertEquals(hit, Suspending.await(c -> l1.read("k", null, null, null, c)));
        Map<String, L1Read> expected = new LinkedHashMap<>();
        expected.put("k", hit);
        expected.put("absent", miss);
        assertEquals(expected, Suspending.await(c -> l1.readAll(List.of("k", "absent"), Map.of(), Duration.ofMillis(500), c)));

        Suspending.await(c -> l1.invalidate(List.of("k"), FRAME, c));
        assertEquals(miss, Suspending.await(c -> l1.read("k", null, null, null, c)));
    }

    @Test
    void capabilitiesKeepTheirIdentityAndNull() {
        L1Store l1 = FutureL1Store.adapt(new MapStore());

        ConditionalL1Store conditional = l1.conditional();
        assertSame(conditional, l1.conditional());
        assertNull(l1.versioned());
        SetL1Store sets = l1.sets();
        assertSame(sets, l1.sets());

        assertSame(L1ConditionalWrite.Written.INSTANCE, Suspending.await(c -> conditional.putIfMatch("k", null, VALUE, null, FRAME, c)));
        assertEquals(new L1ConditionalRead.Unchanged(1), Suspending.await(c -> conditional.readIfChanged("k", Digest.of(VALUE), null, c)));
        assertSame(L1ConditionalWrite.Written.INSTANCE, Suspending.await(c -> conditional.putIfMatch("d", null, VALUE, null, FRAME, c)));
        assertSame(L1ConditionalWrite.Written.INSTANCE, Suspending.await(c -> conditional.deleteIfMatch("d", Digest.of(VALUE), FRAME, c)));
        assertEquals(new L1ConditionalWrite.Conflict(null), Suspending.await(c -> conditional.deleteIfMatch("d", Digest.of(VALUE), FRAME, c)));
        List<byte[]> members = List.of(new byte[] {2});
        assertEquals(Integer.valueOf(1), Suspending.<Integer>await(c -> sets.addMembers("k", members, null, FRAME, c)));
        assertNull(Suspending.<Integer>await(c -> sets.addMembers("set", members, null, FRAME, c)));
    }

    @Test
    void failedFutureThrowsTheOriginalException() {
        IllegalStateException failure = new IllegalStateException("L1 down");
        L1Store l1 = FutureL1Store.adapt(new MapStore() {
            @Override
            public CompletableFuture<L1Fill> fill(Fill fill) {
                return CompletableFuture.supplyAsync(() -> {
                    throw failure;
                });
            }
        });
        Fill fill = new Fill("k", new Token(NodeId.random(), 1), VALUE, Duration.ofMinutes(1));

        assertSame(failure, assertThrows(IllegalStateException.class, () -> Suspending.await(c -> l1.fill(fill, c))));
    }

    @Test
    void cancelledFutureFailsAsAnL1FaultNotAsACancellation() {
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        L1Store l1 = FutureL1Store.adapt(new MapStore() {
            @Override
            public CompletableFuture<Void> release(String key, Token token) {
                return cancelled;
            }
        });
        Token token = new Token(NodeId.random(), 1);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> Suspending.await(c -> l1.release("k", token, c))
        );
        // CancellationException 也是 IllegalStateException 的子类：要求恰好是 IllegalStateException
        assertEquals(IllegalStateException.class, failure.getClass());
        assertInstanceOf(CancellationException.class, failure.getCause());
    }

    /**
     * 用 Java 实现的内存存储：支持条件读写与集合成员写入，不支持按版本写入。只演示 SPI 的形状，不实现令牌与到期时间。
     */
    static class MapStore implements FutureL1Store, FutureConditionalL1Store, FutureSetL1Store {
        private final Map<String, Envelope> entries = new ConcurrentHashMap<>();

        private L1Read readNow(String key) {
            Envelope envelope = entries.get(key);
            return envelope == null ? new L1Read.Miss(false) : new L1Read.Hit(envelope, 1, null, false);
        }

        @Override
        public CompletableFuture<L1Read> read(String key, LeaseRequest lease, RefreshRequest refresh, Duration timeout) {
            return completedFuture(readNow(key));
        }

        @Override
        public CompletableFuture<Map<String, L1Read>> readAll(
            Collection<String> keys,
            Map<String, LeaseRequest> leases,
            Duration timeout
        ) {
            Map<String, L1Read> reads = new LinkedHashMap<>();
            keys.forEach(key -> reads.put(key, readNow(key)));
            return completedFuture(reads);
        }

        @Override
        public CompletableFuture<L1Fill> fill(Fill fill) {
            entries.put(fill.getKey(), fill.getEnvelope());
            return completedFuture(new L1Fill.Filled(false));
        }

        @Override
        public CompletableFuture<List<L1Fill>> fillAll(List<Fill> fills) {
            return completedFuture(fills.stream().map(item -> fill(item).join()).toList());
        }

        @Override
        public CompletableFuture<Void> release(String key, Token token) {
            return completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> reclaim(String key, ContentFingerprint observed, LeaseRequest lease) {
            return completedFuture(false);
        }

        @Override
        public CompletableFuture<Void> put(String key, Envelope envelope, Duration ttl, byte[] frame) {
            entries.put(key, envelope);
            return completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putAll(List<Put> puts, byte[] frame) {
            puts.forEach(item -> entries.put(item.getKey(), item.getEnvelope()));
            return completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> invalidate(Collection<String> keys, byte[] frame) {
            keys.forEach(entries::remove);
            return completedFuture(null);
        }

        @Override
        public FutureConditionalL1Store conditional() {
            return this;
        }

        @Override
        public FutureVersionedL1Store versioned() {
            return null;
        }

        @Override
        public FutureSetL1Store sets() {
            return this;
        }

        @Override
        public CompletableFuture<L1ConditionalRead> readIfChanged(String key, Digest known, Duration timeout) {
            if (!(entries.get(key) instanceof Envelope.Scalar scalar)) {
                return completedFuture(L1ConditionalRead.Absent.INSTANCE);
            }
            if (Digest.of(scalar).equals(known)) {
                return completedFuture(new L1ConditionalRead.Unchanged(1));
            }
            return completedFuture(new L1ConditionalRead.Changed(scalar, 1));
        }

        @Override
        public synchronized CompletableFuture<L1ConditionalWrite> putIfMatch(
            String key,
            Digest expected,
            Envelope.Scalar envelope,
            Duration ttl,
            byte[] frame
        ) {
            Digest current = entries.get(key) instanceof Envelope.Scalar scalar ? Digest.of(scalar) : null;
            if (!Objects.equals(current, expected)) {
                return completedFuture(new L1ConditionalWrite.Conflict(current));
            }
            entries.put(key, envelope);
            return completedFuture(L1ConditionalWrite.Written.INSTANCE);
        }

        @Override
        public synchronized CompletableFuture<L1ConditionalWrite> deleteIfMatch(String key, Digest expected, byte[] frame) {
            Digest current = entries.get(key) instanceof Envelope.Scalar scalar ? Digest.of(scalar) : null;
            if (!Objects.equals(current, expected)) {
                return completedFuture(new L1ConditionalWrite.Conflict(current));
            }
            entries.remove(key);
            return completedFuture(L1ConditionalWrite.Written.INSTANCE);
        }

        @Override
        public synchronized CompletableFuture<Integer> addMembers(String key, Collection<byte[]> members, Duration ttl, byte[] frame) {
            // 标量值不能当作集合：不修改，返回其格式版本
            if (entries.get(key) instanceof Envelope.Scalar) {
                return completedFuture(1);
            }
            List<byte[]> merged = new ArrayList<>();
            if (entries.get(key) instanceof Envelope.Members current) {
                merged.addAll(current.getMembers());
            }
            merged.addAll(members);
            entries.put(key, new Envelope.Members(merged));
            return completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<Integer> removeMembers(String key, Collection<byte[]> members, Duration ttl, byte[] frame) {
            if (entries.get(key) instanceof Envelope.Scalar) {
                return completedFuture(1);
            }
            entries.remove(key);
            return completedFuture(null);
        }
    }
}
