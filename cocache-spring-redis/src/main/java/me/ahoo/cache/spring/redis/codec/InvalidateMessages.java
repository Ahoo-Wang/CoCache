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

package me.ahoo.cache.spring.redis.codec;

import me.ahoo.cache.eventbus.InvalidateEvent;

/**
 * Messages .
 *
 * @author ahoo wang
 */
public final class InvalidateMessages {
    public static final String DELIMITER = "@";
    
    public static String ofClientId(String clientId) {
        return InvalidateEvent.TYPE + DELIMITER + clientId;
    }
    
    public static String getPublisherIdFromMessageBody(String msgBody) {
        String[] typeWithPublisherId = msgBody.split(DELIMITER);
        if (2 != typeWithPublisherId.length) {
            throw new IllegalArgumentException("msgBody illegal:[" + msgBody + "].");
        }
        return typeWithPublisherId[1];
    }
    
}
