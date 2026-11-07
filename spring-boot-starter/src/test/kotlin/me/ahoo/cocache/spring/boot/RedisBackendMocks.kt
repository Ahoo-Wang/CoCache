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

package me.ahoo.cocache.spring.boot

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.redis.RedisBackend

/**
 * `RedisBackend` 的替身，`unwrap` 按类型返回替身自身。MockK 的替身连默认方法 `unwrap` 一起替换：严格的替身没打桩时抛出，
 * 宽松的替身返回类型不对的对象（调用处强转时 `ClassCastException`）。生产代码一律带类型地 `unwrap(RedisBackend::class.java)`，
 * 测试的替身都从这里取。
 */
internal fun redisBackendMock(relaxed: Boolean = false): RedisBackend = mockk<RedisBackend>(
    relaxed = relaxed
).also { redis ->
    every { redis.unwrap(any<Class<Any>>()) } answers { firstArg<Class<*>>().takeIf { it.isInstance(self) }?.let { self } }
}
