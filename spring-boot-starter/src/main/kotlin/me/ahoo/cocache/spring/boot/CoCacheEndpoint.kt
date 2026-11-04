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
 * 缓存运维端点（RFC 0003 §4.14）：列出缓存及其指标、查看单个缓存、清空本节点的 L2、按 key 查看与强制失效。
 *
 * 按 key 的操作以**编码后的 key**（存储 key 中 `{keyPrefix}{namespace}:` 之后的部分）为路径参数，适用于任意 key 类型；
 * key 中可以含 `/`（匹配剩余的全部路径段）。缓存不存在时什么也不做，返回 `null`（[clearLocal] 返回 `false`）：HTTP 上都是 404
 * （DELETE 由 [CoCacheEndpointWebExtension] 映射，否则 web 层把 `null` 映射为 204，拼错的缓存名会静默成功）；空 key 为 HTTP 400。
 */
@Endpoint(id = CoCacheEndpoint.ID)
class CoCacheEndpoint @JvmOverloads constructor(
    private val runtime: CacheRuntime,
    private val properties: CoCacheProperties? = null,
    private val switches: CoCacheEndpointProperties = CoCacheEndpointProperties.DEFAULT,
) {
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
     *
     * @return 缓存是否存在（不存在时什么也不做）
     */
    @DeleteOperation
    fun clearLocal(@Selector name: String): Boolean {
        val admin = runtime.admin(name) ?: return false
        admin.clearLocal()
        return true
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
     * 按 key 删除，全集群生效，幂等（[CacheAdmin.invalidateEncoded]）。按类型映射（RFC 0005 §4.2）：
     * `Cache` 照常失效；`VersionedCache` 保留版本（`evict`）；`Store` 同 `delete`、`VersionedStore` 同 `purge`、`SetStore` 同 `clear`，
     * 三者失败时抛出、不重试。
     *
     * 删除唯一副本的操作要显式开关（[CoCacheEndpointProperties]，RFC 0005 §4.9）：`Store` 与 `SetStore` 要 `allow-store-delete`，
     * `VersionedStore` 要 `allow-purge`；没有开启时抛出 [DeletionNotAllowedException]（HTTP 403），不修改任何东西。
     *
     * @param purge `true` 时清除并丢掉版本（[CacheAdmin.purgeEncoded]，要 `allow-purge`）：只用于带版本的类型，
     *   `VersionedCache` 上不带它时是 `evict`；其它类型带它时为非法请求（400）
     */
    @DeleteOperation
    fun invalidate(
        @Selector name: String,
        @Selector(match = Selector.Match.ALL_REMAINING) key: Array<String>,
        purge: Boolean? = null,
    ): InvalidationReport? {
        val admin = runtime.admin(name) ?: return null
        val encodedKey = encodedKeyOf(key)
        if (purge == true) {
            if (admin.keyDeletion != CacheAdmin.KeyDeletion.EVICT && admin.keyDeletion != CacheAdmin.KeyDeletion.PURGE) {
                val reason = "Cache[$name] is not versioned: purge applies only to VersionedCache and VersionedStore."
                throw InvalidEndpointRequestException(reason, reason)
            }
            requireAllowed(switches.allowPurge, name, CacheAdmin.KeyDeletion.PURGE, "allow-purge")
            admin.purgeEncoded(encodedKey)
            return InvalidationReport(name, encodedKey, CacheAdmin.KeyDeletion.PURGE)
        }
        when (admin.keyDeletion) {
            CacheAdmin.KeyDeletion.DELETE, CacheAdmin.KeyDeletion.CLEAR ->
                requireAllowed(switches.allowStoreDelete, name, admin.keyDeletion, "allow-store-delete")
            CacheAdmin.KeyDeletion.PURGE -> requireAllowed(
                switches.allowPurge,
                name,
                admin.keyDeletion,
                "allow-purge"
            )
            CacheAdmin.KeyDeletion.INVALIDATE, CacheAdmin.KeyDeletion.EVICT -> Unit
        }
        admin.invalidateEncoded(encodedKey)
        return InvalidationReport(name, encodedKey, admin.keyDeletion)
    }

    private fun requireAllowed(allowed: Boolean, name: String, deletion: CacheAdmin.KeyDeletion, switch: String) {
        if (!allowed) {
            throw DeletionNotAllowedException(
                "Cache[$name] - ${deletion.name.lowercase()} by key deletes the only copy: " +
                    "set ${CoCacheEndpointProperties.PREFIX}.$switch=true to allow it."
            )
        }
    }

    /**
     * 按 key 删除的结果。
     *
     * @param deletion 这次删除做了什么（按缓存的类型，[CacheAdmin.KeyDeletion]）
     */
    data class InvalidationReport(val cache: String, val key: String, val deletion: CacheAdmin.KeyDeletion)

    /**
     * 按 key 删除会删除唯一副本，而 [CoCacheEndpointProperties] 没有开启对应的开关：没有修改任何东西。
     */
    class DeletionNotAllowedException(message: String) : IllegalStateException(message)

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

    /**
     * @param type 用户 API 类型（`Cache`、`VersionedCache`、`Store`、`VersionedStore`、`SetStore`）
     * @param layered `false` 即“未分层”：直接经 `CacheRuntime.create` 创建，配置文件与定制器对它都不生效（RFC 0005 §4.8，S20）
     * @param ignoredConfiguration 未分层的缓存上不生效的按缓存配置条目（`cocache.caches.<name>`）
     */
    data class CacheReport(
        val name: String,
        val type: String,
        val layered: Boolean,
        val ignoredConfiguration: List<String>,
        val localSize: Long,
        val loads: Long,
        val loadNanos: Long,
        val metrics: Map<String, Long>,
    )

    private fun CacheAdmin.asReport(): CacheReport {
        val snapshot = metrics
        return CacheReport(
            name = name,
            type = apiType.simpleName,
            layered = layered,
            ignoredConfiguration = properties?.let { UnlayeredConfigurationCheck.ignoredEntries(it, this) } ?: emptyList(),
            localSize = localSize,
            loads = snapshot.loads,
            loadNanos = snapshot.loadNanos,
            metrics = CacheCounters.ALL.filter { it.recorded(snapshot) }.associate { it.key to it.read(snapshot) },
        )
    }
}
