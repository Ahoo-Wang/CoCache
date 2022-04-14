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

package me.ahoo.cache.eventbus;

import me.ahoo.cache.client.MapClientSideCache;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SimpleInvalidateEventBusTest .
 *
 * @author ahoo wang
 */
class GuavaInvalidateEventBusTest {
    private static final String CLIENT_ID = "GuavaInvalidateEventBusTest";
    
    @SneakyThrows
    @Test
    void publish() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        GuavaInvalidateEventBus eventBus = new GuavaInvalidateEventBus(CLIENT_ID);
        InvalidateEvent publishedEvent = InvalidateEvent.of("publish", "");
        InvalidateSubscriber subscriber = new MapClientSideCache() {
            @Override
            public void onInvalidate(@Nonnull InvalidateEvent invalidateEvent) {
                super.onInvalidate(invalidateEvent);
                Assertions.assertEquals(publishedEvent, invalidateEvent);
                countDownLatch.countDown();
            }
        };
        eventBus.register(subscriber);
        eventBus.publish(publishedEvent);
        Assertions.assertTrue(countDownLatch.await(1, TimeUnit.SECONDS));
    }
    
    @SneakyThrows
    @Test
    void publishWhenNoLoop() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        GuavaInvalidateEventBus eventBus = new GuavaInvalidateEventBus(CLIENT_ID);
        InvalidateEvent publishedEvent = InvalidateEvent.of("publishWhenNoLoop", CLIENT_ID);
        InvalidateSubscriber subscriber = invalidateEvent -> {
            Assertions.assertEquals(publishedEvent, invalidateEvent);
            countDownLatch.countDown();
        };
        eventBus.register(subscriber);
        eventBus.publish(publishedEvent);
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS));
    }
    
    @SneakyThrows
    @Test
    void unregister() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        GuavaInvalidateEventBus eventBus = new GuavaInvalidateEventBus(CLIENT_ID);
        InvalidateEvent publishedEvent = InvalidateEvent.of("unregister", "");
        InvalidateSubscriber subscriber = invalidateEvent -> {
            Assertions.assertEquals(publishedEvent, invalidateEvent);
            countDownLatch.countDown();
        };
        eventBus.register(subscriber);
        eventBus.unregister(subscriber);
        eventBus.publish(publishedEvent);
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS));
    }
}
