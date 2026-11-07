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

import org.apache.commons.logging.LogFactory
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor
import org.springframework.boot.context.properties.bind.AbstractBindHandler
import org.springframework.boot.context.properties.bind.BindContext
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.context.properties.source.ConfigurationPropertyName

/**
 * 策略各层（`cocache.caches.[name]`、`cocache.defaults`、`cocache.overrides`）下不认识的配置项：多半是拼写错误（如 `ttl-forever`、
 * 漏了 `load-` 的 `max-concurrent`），或全局层中 6.x 留下的键（`cocache.defaults.ttl`、`cocache.defaults.key-prefix`，8.x 的字段在
 * `cache`、`store` 两节之下，key 前缀是 `cocache.key-prefix`）。Spring Boot 的绑定忽略不认识的键，写错的配置于是不生效、也没有任何提示
 * （事故中写下的覆盖尤其如此；全局层的影响面更大，被忽略的 `key-prefix` 让共用一个 Redis 的应用落到同一个 keyspace），因此按
 * `cocache.caches-validation` 处理：`fail`（默认）启动失败，`warn` 告警（[check]）。`cocache` 下其它配置项（如 `cocache.redis`）不在此列。
 *
 * 判定接入 [CoCacheProperties] 的正式绑定（[ConfigurationPropertiesBindHandlerAdvisor]），不另做一次绑定：转换服务与
 * `@ConfigurationPropertiesBinding` 转换器因此与正式绑定相同，正式绑定接受的值不会在这里失败（#848）。绑定 `cocache` 时旁路记下
 * 绑定到了属性的配置项（Spring Boot 的 [NoUnboundElementsBindHandler]），结束时 `cocache.caches` 下其余的配置项即不认识的键。
 * 宽松命名（`localMaxAge`、`local-maxage`、环境变量 `COCACHE_CACHES_USER_LOCALMAXAGE`）与列表下标（`unlock[0]`）因此与正式绑定的规则一致。
 * 只记录，不改变绑定的结果：外层处理器的回调照常执行，其它配置类的绑定不受影响。
 * advisor 作用于所有配置类的绑定，只有根目标是 [CoCacheProperties] 的那次才记录：应用自己绑定到 `cocache` 前缀的其它配置类
 * （如只读取 `enabled` 的投影）不认识 `cocache.caches.*`，不能覆盖 [CoCacheProperties] 的记录（#867）。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class UnknownCacheKeys : ConfigurationPropertiesBindHandlerAdvisor {
    @Volatile
    private var recorded: List<String> = emptyList()

    /**
     * 最近一次绑定 [CoCacheProperties] 时各层下不认识的键（完整的配置键，排序）。
     */
    val keys: List<String>
        get() = recorded

    override fun apply(bindHandler: BindHandler): BindHandler = Recording(bindHandler)

    /**
     * 有不认识的键时按 [validation] 启动失败（[IllegalStateException]，一条信息列出全部层）或告警（每层一条）。
     */
    fun check(validation: CoCacheProperties.Validation) {
        val unknown = keys
        if (unknown.isEmpty()) {
            return
        }
        val messages = LAYERS.mapNotNull { layer ->
            val inLayer = unknown.filter { layer.name.isAncestorOf(ConfigurationPropertyName.of(it)) }
            if (inLayer.isEmpty()) null else "${layer.name} sets unknown keys $inLayer: ${layer.hint}"
        }
        check(validation == CoCacheProperties.Validation.WARN) { messages.joinToString(" ") }
        messages.forEach { log.warn(it) }
    }

    /**
     * 受检查的一层：配置前缀与信息中的提示。
     */
    private class Layer(name: String, val hint: String) {
        val name: ConfigurationPropertyName = ConfigurationPropertyName.of(name)
    }

    private inner class Recording(parent: BindHandler) : AbstractBindHandler(parent) {
        private val bound = NoUnboundElementsBindHandler(BindHandler.DEFAULT)

        override fun <T : Any> onStart(name: ConfigurationPropertyName, target: Bindable<T>, context: BindContext): Bindable<T>? {
            bound.onStart(name, target, context)
            return super.onStart(name, target, context)
        }

        override fun onSuccess(name: ConfigurationPropertyName, target: Bindable<*>, context: BindContext, result: Any): Any? {
            bound.onSuccess(name, target, context, result)
            return super.onSuccess(name, target, context, result)
        }

        override fun onFinish(name: ConfigurationPropertyName, target: Bindable<*>, context: BindContext, result: Any?) {
            super.onFinish(name, target, context, result)
            if (context.depth != 0 || name != ROOT || target.type.resolve() != CoCacheProperties::class.java) {
                return
            }
            recorded = try {
                bound.onFinish(name, target, context, result)
                emptyList()
            } catch (e: UnboundConfigurationPropertiesException) {
                e.unboundProperties.map { it.name }
                    .filter { name -> LAYERS.any { it.name.isAncestorOf(name) } }
                    .map { it.toString() }
                    .sorted()
            }
        }
    }

    private companion object {
        private val log = LogFactory.getLog(UnknownCacheKeys::class.java)
        private const val PREFIX = CoCacheProperties.PREFIX
        private val ROOT = ConfigurationPropertyName.of(PREFIX)
        private val LAYERS = listOf(
            Layer("$PREFIX.caches", "misspelled, or not a per-cache key."),
            Layer(
                "$PREFIX.defaults",
                "misspelled, or a 6.x key: fields go under $PREFIX.defaults.cache or $PREFIX.defaults.store, " +
                    "and the key prefix is $PREFIX.key-prefix."
            ),
            Layer(
                "$PREFIX.overrides",
                "misspelled, or not under $PREFIX.overrides.cache or $PREFIX.overrides.store."
            ),
        )
    }
}
