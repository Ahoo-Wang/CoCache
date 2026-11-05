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

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * 运维端点 `cocache` 的开关（`management.endpoint.cocache.*`，RFC 0005 §4.9 运维入口）。写操作本身照常经 Spring Boot 的
 * `management.endpoint.cocache.access` 开放；这里另外挡住删除唯一副本的按 key 删除，默认都关闭：
 * 派生副本（`Cache`）的失效与 `VersionedCache` 保留版本的 `evict` 不受影响。
 *
 * 构造函数绑定的默认值用 [DefaultValue] 声明，理由同 [CoCacheProperties]。
 */
@ConfigurationProperties(prefix = CoCacheEndpointProperties.PREFIX)
data class CoCacheEndpointProperties @ConstructorBinding constructor(
    /**
     * 允许按 key 删除 `Store` 与 `SetStore` 的 key（`DELETE /actuator/cocache/{name}/{key}`）：删除的是唯一副本，无法从数据源重建。
     */
    @DefaultValue("false")
    val allowStoreDelete: Boolean,
    /**
     * 允许按 key 清除并丢掉版本（`purge`）：`VersionedStore` 的按 key 删除，与 `VersionedCache` 上的 `?purge=true`。
     * 之后任何版本都能写入，迟到的旧版本也是。
     */
    @DefaultValue("false")
    val allowPurge: Boolean,
) {
    companion object {
        const val PREFIX = "management.endpoint.cocache"

        /**
         * 两个开关都关闭（默认）。
         */
        @JvmField
        val DEFAULT = CoCacheEndpointProperties(allowStoreDelete = false, allowPurge = false)
    }
}
