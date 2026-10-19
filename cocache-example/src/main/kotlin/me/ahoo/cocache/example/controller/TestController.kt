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

package me.ahoo.cocache.example.controller

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.cocache.example.cache.UserCache
import me.ahoo.cocache.example.cache.UserExtendInfoJoinCache
import me.ahoo.cocache.example.model.User
import me.ahoo.cocache.example.model.UserExtendInfo
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("test")
class TestController(
    private val userCache: UserCache,
    private val userExtendInfoJoinCache: UserExtendInfoJoinCache,
    @param:Qualifier("product")
    private val productCache: Cache<String, String>,
) {

    @GetMapping("{id}")
    fun get(@PathVariable id: String): User? = userCache[id]

    @PostMapping("{id}")
    fun set(@PathVariable id: String): String {
        val user = User(id, UUID.randomUUID().toString())
        userCache.put(user.id, user)
        return user.id
    }

    /**
     * 先更新数据源，再失效（事务内用 `TransactionalInvalidator` 登记到提交之后）。
     */
    @DeleteMapping("{id}")
    fun invalidate(@PathVariable id: String) = userCache.invalidate(id)

    @GetMapping("product/{id}")
    fun product(@PathVariable id: String): String? = productCache[id]

    /**
     * 组合视图：做的就是两次批量读取（扩展信息一次 `getAll`，去重后的 userId 一次 `getAll`）。
     */
    @GetMapping("extend")
    fun extendInfos(ids: List<String>): List<JoinValue<UserExtendInfo, String, User>> {
        return userExtendInfoJoinCache.getAll(ids).values.toList()
    }

    /**
     * Spring Cache 注解：`sync = true` 时回源受 lease 与失效戳保护；key 必须与缓存的 key 类型一致（这里是 String）。
     */
    @Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true)
    @GetMapping("spring/{id}")
    fun getBySpring(@PathVariable id: String, name: String?): User = User(id, name ?: UUID.randomUUID().toString())
}
