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

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.boot.actuate.endpoint.web.annotation.EndpointWebExtension

/**
 * [CoCacheEndpoint] 的 HTTP 状态码：未知缓存的 `DELETE` 返回 404，而不是 web 层为 `null` 映射的 204（RFC 0005 §4.9、§12 M13），
 * 拼错的缓存名不再静默成功。与 Spring Boot 的 `CachesEndpointWebExtension` 相同的做法：同一路径的操作替换端点上的操作，
 * 其它技术（JMX）照常使用端点本身。
 */
@EndpointWebExtension(endpoint = CoCacheEndpoint::class)
class CoCacheEndpointWebExtension(private val delegate: CoCacheEndpoint) {

    /**
     * [CoCacheEndpoint.clearLocal]：204；未知缓存 404。
     */
    @DeleteOperation
    fun clearLocal(@Selector name: String): WebEndpointResponse<Void> {
        val cleared = delegate.clearLocal(name)
        return WebEndpointResponse(
            if (cleared) WebEndpointResponse.STATUS_NO_CONTENT else WebEndpointResponse.STATUS_NOT_FOUND
        )
    }

    /**
     * [CoCacheEndpoint.invalidate]：200 并返回失效的 key；未知缓存 404。
     */
    @DeleteOperation
    fun invalidate(
        @Selector name: String,
        @Selector(match = Selector.Match.ALL_REMAINING) key: Array<String>
    ): WebEndpointResponse<CoCacheEndpoint.InvalidationReport> {
        val report = delegate.invalidate(name, key) ?: return WebEndpointResponse(WebEndpointResponse.STATUS_NOT_FOUND)
        return WebEndpointResponse(report, WebEndpointResponse.STATUS_OK)
    }
}
