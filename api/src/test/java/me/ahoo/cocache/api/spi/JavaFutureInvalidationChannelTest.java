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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Java 实现者只实现返回 {@link CompletableFuture} 的发布：没有 {@code Continuation} 参数；
 * 经 {@link FutureInvalidationChannel#adapt(FutureInvalidationChannel)} 得到 {@link InvalidationChannel}。
 */
class JavaFutureInvalidationChannelTest {
    private static final byte[] FRAME = InvalidationFrame.encode(NodeId.random(), List.of("k"));

    @Test
    void publishAndSubscribeRunThroughTheAdapter() {
        RecordingChannel recording = new RecordingChannel();
        InvalidationChannel channel = FutureInvalidationChannel.adapt(recording);

        Suspending.await(c -> channel.publish("ns", FRAME, c));
        assertEquals(List.of("ns"), recording.published);
        InvalidationListener listener = new InvalidationListener() {
            @Override
            public void onInvalidated(List<String> keys, NodeId origin) {
            }

            @Override
            public void onReset() {
            }
        };
        assertSame(recording.subscription, channel.subscribe("ns", listener));
    }

    @Test
    void failedPublishThrowsTheOriginalException() {
        IllegalStateException failure = new IllegalStateException("channel down");
        InvalidationChannel channel = FutureInvalidationChannel.adapt(new RecordingChannel() {
            @Override
            public CompletableFuture<Void> publish(String namespace, byte[] frame) {
                return CompletableFuture.supplyAsync(() -> {
                    throw failure;
                });
            }
        });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> Suspending.await(c -> channel.publish("ns", FRAME, c))));
    }

    static class RecordingChannel implements FutureInvalidationChannel {
        final List<String> published = new CopyOnWriteArrayList<>();
        final Subscription subscription = () -> { };

        @Override
        public Subscription subscribe(String namespace, InvalidationListener listener) {
            return subscription;
        }

        @Override
        public CompletableFuture<Void> publish(String namespace, byte[] frame) {
            published.add(namespace);
            return CompletableFuture.completedFuture(null);
        }
    }
}
