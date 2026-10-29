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

package me.ahoo.cocache.example.repository

import kotlinx.coroutines.delay
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

/**
 * 协程示例的数据源：挂起的读写（真实应用中是 R2DBC、WebClient 等非阻塞客户端）。以用户 id 保存昵称，不存在时返回 `null`。
 */
@Repository
class ProfileRepository {
    private val nicknames = ConcurrentHashMap<String, String>()

    suspend fun find(id: String): String? {
        delay(SIMULATED_IO_MILLIS)
        return nicknames[id]
    }

    suspend fun save(id: String, nickname: String) {
        delay(SIMULATED_IO_MILLIS)
        nicknames[id] = nickname
    }

    private companion object {
        const val SIMULATED_IO_MILLIS = 5L
    }
}
