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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.example.cache.UserCache
import me.ahoo.cocache.example.cache.UserExtendInfoJoinCache
import me.ahoo.cocache.example.controller.TestController
import me.ahoo.cocache.example.model.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager

/**
 * 示例应用冒烟测试（需要 localhost:6379 的 Redis）。
 */
@SpringBootTest(properties = ["spring.main.web-application-type=none"])
class AppServerTest {
    @Autowired
    lateinit var userCache: UserCache

    @Autowired
    @Qualifier("product")
    lateinit var productCache: Cache<String, String>

    @Autowired
    lateinit var cacheManager: CacheManager

    @Autowired
    lateinit var joinCache: UserExtendInfoJoinCache

    @Autowired
    lateinit var controller: TestController

    @Test
    fun cachesWork() {
        val id = java.util.UUID.randomUUID().toString()
        assertThat(userCache[id]).isEqualTo(User(id, "user-$id"))
        assertThat(productCache["p-$id"]).isEqualTo("product-p-$id")
        assertThat(cacheManager.cacheNames).contains("UserCache", "UserExtendInfoCache", "product")
        assertThat(
            controller.extendInfos(listOf("e-$id")).single().secondValue
        ).isEqualTo(User("user-of-e-$id", "user-user-of-e-$id"))
        assertThat(joinCache["e-$id"]?.joinKey).isEqualTo("user-of-e-$id")
        // @Cacheable(sync = true)：失效后由被注解的方法回源（受 lease 保护），结果写入同一个缓存
        controller.invalidate(id)
        assertThat(controller.getBySpring(id, "from-method")).isEqualTo(User(id, "from-method"))
        assertThat(controller.getBySpring(id, "cached")).isEqualTo(User(id, "from-method"))
        assertThat(userCache[id]).isEqualTo(User(id, "from-method"))
    }
}
