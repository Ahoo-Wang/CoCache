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

import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyInspection
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector

/**
 * 6.0 缓存运维端点（RFC 0003 §4.14）：列出缓存及其指标、查看单个缓存、清空本节点的 L2、按 key 查看与强制失效。
 *
 * 按 key 的操作以**编码后的 key**（存储 key 中 `{keyPrefix}{namespace}:` 之后的部分）为路径参数，适用于任意 key 类型；
 * key 中可以含 `/`（匹配剩余的全部路径段）。缓存不存在时返回 `null`：GET 为 HTTP 404，DELETE 为 HTTP 204（什么也不做）；空 key 为 HTTP 400。
 */
@Endpoint(id = CoCacheEndpoint.ID)
class CoCacheEndpoint(private val runtime: CacheRuntime) {
    companion object {
        const val ID = "cocache"
    }

    @ReadOperation
    fun caches(): List<CacheReport> {
        return runtime.cacheNames.sorted().mapNotNull { runtime.admin(it)?.asReport() }
    }

    @ReadOperation
    fun cache(@Selector name: String): CacheReport? {
        return runtime.admin(name)?.asReport()
    }

    /**
     * 清空本节点的 L2（L1 是共享存储，不受影响）。
     */
    @DeleteOperation
    fun clearLocal(@Selector name: String) {
        runtime.admin(name)?.clearLocal()
    }

    /**
     * 查看一个 key 在本节点 L2 与 L1 中的状态，不加载、不申请 lease、不写任何一层（[me.ahoo.cocache.core.CacheAdmin.inspect]）。
     * 返回的值是 `toString()` 的截断展示：端点访问权限应与能读取缓存数据的权限一致。
     */
    @ReadOperation
    fun inspect(
        @Selector name: String,
        @Selector(match = Selector.Match.ALL_REMAINING) key: Array<String>
    ): KeyInspection? {
        return runtime.admin(name)?.inspect(encodedKeyOf(key))
    }

    /**
     * 按 key 强制失效，走正常的失效路径（全集群生效），幂等（[me.ahoo.cocache.core.CacheAdmin.invalidateEncoded]）。
     * `SetCache` 上等价于 `clear(key)`。
     */
    @DeleteOperation
    fun invalidate(
        @Selector name: String,
        @Selector(match = Selector.Match.ALL_REMAINING) key: Array<String>
    ): InvalidationReport? {
        val admin = runtime.admin(name) ?: return null
        val encodedKey = encodedKeyOf(key)
        admin.invalidateEncoded(encodedKey)
        return InvalidationReport(name, encodedKey)
    }

    /**
     * 强制失效的结果。
     */
    data class InvalidationReport(val cache: String, val key: String)

    /**
     * 剩余路径段以 `/` 连接为编码后的 key。Spring 会去掉空段与首尾空白，因此含 `//`、首尾 `/` 或空白的 key 无法经端点精确寻址；
     * 空 key（如结尾的 `/`）按非法请求（400）拒绝，不会失效空串 key。
     */
    private fun encodedKeyOf(segments: Array<String>): String {
        val key = segments.joinToString("/")
        if (key.isEmpty()) {
            throw InvalidEndpointRequestException("Key must not be empty.", "Key must not be empty.")
        }
        return key
    }

    data class CacheReport(
        val name: String,
        val localSize: Long,
        val loads: Long,
        val loadNanos: Long,
        val metrics: Map<String, Long>,
    )

    private fun CacheAdmin.asReport(): CacheReport {
        val snapshot = metrics
        return CacheReport(
            name = name,
            localSize = localSize,
            loads = snapshot.loads,
            loadNanos = snapshot.loadNanos,
            metrics = CacheCounters.ALL.filter { it.recorded(snapshot) }.associate { it.key to it.read(snapshot) },
        )
    }
}
