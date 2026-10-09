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

package me.ahoo.cache.spring.redis

import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cache.test.MultipleInstanceSyncSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.*

class RedisMultipleInstanceSyncTest : MultipleInstanceSyncSpec<String, String>() {
    private lateinit var redis: RedisTestSupport

    @BeforeEach
    override fun setup() {
        redis = RedisTestSupport()
        super.setup()
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        redis.close()
    }

    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("sync-test:")

    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()

    override fun createDistributedCache(): DistributedCache<String> {
        return RedisDistributedCache(redis.redisTemplate, StringToStringCodecExecutor(redis.redisTemplate))
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = redis.newEventBus()

    override fun createCacheName(): String = "RedisMultipleInstanceSyncTest"

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
