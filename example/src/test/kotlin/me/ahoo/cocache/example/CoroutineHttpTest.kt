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

package me.ahoo.cocache.example

import me.ahoo.cocache.example.model.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.UUID

/**
 * 经 HTTP 调用 `suspend fun` 处理器方法（需要 localhost:6379 的 Redis）：验证 WebFlux 与协程的桥接（kotlinx-coroutines-reactor）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoroutineHttpTest {
    @Value("\${local.server.port}")
    var port: Int = 0

    private val timeout = Duration.ofSeconds(10)

    @Test
    fun suspendingHandlers() {
        val client = WebClient.create("http://localhost:$port/coroutine")
        val id = UUID.randomUUID().toString()
        val user = client.get().uri("/user/{id}", id).retrieve().bodyToMono<User>().block(timeout)
        assertThat(user).isEqualTo(User(id, "user-$id"))
        client.put().uri("/profile/{id}?nickname={nickname}", id, "http").retrieve().toBodilessEntity().block(timeout)
        val nickname = client.get().uri("/profile/{id}", id).retrieve().bodyToMono<String>().block(timeout)
        assertThat(nickname).isEqualTo("http")
    }
}
