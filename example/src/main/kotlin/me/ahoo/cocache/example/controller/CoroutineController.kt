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

import kotlinx.coroutines.delay
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.coroutines.suspending
import me.ahoo.cocache.example.cache.UserCache
import me.ahoo.cocache.example.model.User
import me.ahoo.cocache.example.repository.ProfileRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * WebFlux + 协程：处理器方法是 `suspend fun`，经挂起视图读写缓存，等待 Redis 或数据源时不阻塞线程。
 * 挂起视图与同步 API 是同一个缓存（同一个 L2、失效戳、在途加载与 lease），创建一次、保存在字段中复用。
 */
@RestController
@RequestMapping("coroutine")
class CoroutineController(
    userCache: UserCache,
    @Qualifier("profile")
    profileCache: Cache<String, String>,
    private val profileRepository: ProfileRepository,
) {
    private val users = userCache.suspending()
    private val profiles = profileCache.suspending()

    /**
     * 读取：L2 命中不挂起；未命中时经声明的数据源（`CacheLoader<String, User>`）加载。
     */
    @GetMapping("user/{id}")
    suspend fun user(@PathVariable id: String): User? = users.get(id)

    /**
     * 调用级 loader：只在未命中时执行，受与声明的数据源相同的保护（lease、失效戳、按 key 合并）。
     * 它在调用方的 dispatcher 上执行，但脱离结构化并发：请求被取消不会取消这次加载（它可能被同一 key 的其它请求共享）。
     */
    @GetMapping("user/{id}/remote")
    suspend fun remoteUser(@PathVariable id: String, @RequestParam name: String): User? =
        users.get(id) { key ->
            // 模拟远程调用
            delay(SIMULATED_REMOTE_MILLIS)
            User(key, name)
        }

    /**
     * spec 级挂起数据源（`suspendingLoader`，见 `UserCacheConfiguration.profileSpec`）；不存在的昵称按负缓存处理。
     */
    @GetMapping("profile/{id}")
    suspend fun profile(@PathVariable id: String): String? = profiles.get(id)

    /**
     * 写后失效：先更新数据源，再失效（同时清掉负缓存）。在事务中时要先提交、再失效，否则其它实例可能在提交前把旧值加载回缓存。
     */
    @PutMapping("profile/{id}")
    suspend fun updateProfile(@PathVariable id: String, @RequestParam nickname: String) {
        profileRepository.save(id, nickname)
        profiles.invalidate(id)
    }

    private companion object {
        const val SIMULATED_REMOTE_MILLIS = 5L
    }
}
