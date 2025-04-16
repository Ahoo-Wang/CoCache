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
package me.ahoo.cache.example.controller

import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.example.cache.UserCache
import me.ahoo.cache.example.model.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

/**
 * TestController .
 *
 * @author ahoo wang
 */
@RestController
@RequestMapping("test")
class TestController(
    @param:Qualifier("userCache")
    private val userCaching: CoherentCache<String, User>,
    private val userCache: UserCache
) {

    @GetMapping("{id}")
    fun get(@PathVariable id: String): User? {
        return userCache[id]
    }

    @PostMapping("{id}")
    fun set(@PathVariable id: String): String {
        val user = User(id, UUID.randomUUID().toString())
        userCache[user.id] = user
        return user.id
    }

    @Cacheable(cacheNames = ["userCache"])
    @GetMapping("spring/{id}")
    fun getBySpring(@PathVariable id: String, name: String?): User? {
        return User(id, name ?: UUID.randomUUID().toString())
    }
}
