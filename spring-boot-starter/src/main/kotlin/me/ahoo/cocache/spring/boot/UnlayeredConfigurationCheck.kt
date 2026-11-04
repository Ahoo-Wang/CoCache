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
import me.ahoo.cocache.core.CacheCreationListener
import me.ahoo.cocache.core.CacheRuntime
import org.apache.commons.logging.LogFactory

/**
 * 未分层的缓存上不生效的配置（RFC 0005 §4.8，S20）：直接经 `CacheRuntime.create` 创建的缓存不经过任何配置层，
 * `cocache.caches.[name]` 指向它时告警一次，指向 `CoCacheFactory`。只告警、不让启动失败：运维常在事故中写下按缓存的配置，
 * 这时让应用起不来比一条不生效的覆盖更糟；库也可能在启动之后才创建缓存，启动时的检查本来就看不到全部。
 *
 * 创建时向运行时注册监听，并核对已经创建的缓存。端点的 `ignoredConfiguration` 列出同样的条目（[ignoredEntries]）。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class UnlayeredConfigurationCheck(runtime: CacheRuntime, private val properties: CoCacheProperties) :
    CacheCreationListener {
    init {
        runtime.addCreationListener(this)
        runtime.cacheNames.forEach { name -> runtime.admin(name)?.let { created(it) } }
    }

    override fun created(admin: CacheAdmin) {
        val ignored = ignoredEntries(properties, admin)
        if (ignored.isNotEmpty()) {
            log.warn(
                "Cache[${admin.name}] - $ignored don't apply: the cache was created directly through CacheRuntime.create, " +
                    "not CoCacheFactory, so no configuration layer reaches it. Create it through CoCacheFactory."
            )
        }
    }

    companion object {
        private val log = LogFactory.getLog(UnlayeredConfigurationCheck::class.java)

        /**
         * 对 [admin] 不生效的按缓存配置条目（`cocache.caches.<key>`，按名称不区分大小写匹配）；已分层的缓存没有。
         */
        internal fun ignoredEntries(properties: CoCacheProperties, admin: CacheAdmin): List<String> {
            if (admin.layered) {
                return emptyList()
            }
            return properties.caches.keys.filter { it.equals(admin.name, ignoreCase = true) }
                .map { "${CoCacheProperties.PREFIX}.caches.$it" }
        }
    }
}
