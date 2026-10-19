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

import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.core.env.Environment

/**
 * 5.x（以及 6.0 过渡期 `cocache.next.*`）中已删除的配置项：出现即启动失败，并指出替代项。
 *
 * 静默忽略会让 `strict-failure`、`eviction-channel` 等改变语义的配置悄悄失效（例如全集群通道不一致导致失效丢失），
 * 因此按松散绑定规则（含环境变量形式）检查。
 */
internal object RemovedProperties {
    /**
     * 已删除的配置项（或前缀）→ 处理建议。
     */
    private val REMOVED: List<Pair<ConfigurationPropertyName, String>> = listOf(
        "cocache.redis.strict-failure" to "use cocache.defaults.read-failure=throw (or cocache.caches.[name].read-failure=throw) instead",
        "cocache.redis.eviction-channel" to "use cocache.redis.channel-mode (pubsub | sharded) instead",
        "cocache.redis.missing-guard-sentinel" to "it has no replacement in 6.0; remove it",
        "cocache.metrics.client-side-hits" to "it has no replacement in 6.0; remove it",
        "cocache.load.max-concurrent" to "use cocache.defaults.load-max-concurrent (or cocache.caches.[name].load-max-concurrent) instead",
        "cocache.load.acquire-timeout" to "use cocache.defaults.load-acquire-timeout (or cocache.caches.[name].load-acquire-timeout) instead",
        "cocache.load" to "use cocache.defaults.load-* (or cocache.caches.[name].load-*) instead",
        "cocache.next" to "the transitional prefix cocache.next.* became cocache.*; rename the property",
    ).map { (name, advice) -> ConfigurationPropertyName.of(name) to advice }

    /**
     * 出现已删除的配置项时抛出 [IllegalStateException]，消息列出每一项及其替代。
     */
    fun check(environment: Environment) {
        val found = sortedMapOf<String, String>()
        for (source in ConfigurationPropertySources.get(environment)) {
            val iterable = source as? IterableConfigurationPropertySource ?: continue
            for (name in iterable) {
                adviceFor(name)?.let { found.putIfAbsent(name.toString(), it) }
            }
        }
        check(found.isEmpty()) {
            "CoCache 6.0 removed these properties: " + found.entries.joinToString("; ") { (name, advice) -> "[$name] - $advice" } + "."
        }
    }

    /**
     * 最具体的匹配优先：`cocache.load.max-concurrent` 先于 `cocache.load`。
     */
    internal fun adviceFor(name: ConfigurationPropertyName): String? {
        return REMOVED.firstOrNull { (removed, _) -> removed == name || removed.isAncestorOf(name) }?.second
    }
}
