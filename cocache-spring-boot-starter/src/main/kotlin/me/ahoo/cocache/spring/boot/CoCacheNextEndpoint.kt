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
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector

/**
 * 6.0 缓存运维端点（RFC 0003 §4.14）：列出缓存及其指标、查看单个缓存、清空本节点的 L2。
 */
@Endpoint(id = CoCacheNextEndpoint.ID)
class CoCacheNextEndpoint(private val runtime: CacheRuntime) {
    companion object {
        const val ID = "cocachenext"
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
            metrics = CacheCounters.ALL.associate { it.key to it.read(snapshot) },
        )
    }
}
