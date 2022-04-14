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

package me.ahoo.cache.util;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Cache Second Clock .
 *
 * @author ahoo wang
 */
public enum CacheSecondClock implements SecondClock, Runnable {
    INSTANCE(SystemSecondClock.INSTANCE);
    
    /**
     * Tolerate a one-second time limit.
     */
    public static final long ONE_SECOND_PERIOD = Duration.ofSeconds(1).toNanos();
    private final SecondClock actual;
    private final Thread secondTimer;
    private volatile long lastTime;
    
    CacheSecondClock(SecondClock actual) {
        this.actual = actual;
        secondTimer = startTimer();
    }
    
    private Thread startTimer() {
        final Thread timer = new Thread(this);
        timer.setName("CacheSecondClock");
        timer.setDaemon(true);
        timer.start();
        return timer;
    }
    
    @Override
    public long currentTime() {
        return lastTime;
    }
    
    @Override
    public void run() {
        while (!secondTimer.isInterrupted()) {
            this.lastTime = actual.currentTime();
            LockSupport.parkNanos(this, ONE_SECOND_PERIOD);
        }
    }
}
