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
 * `cocache.caches.[name]` 下不认识的配置项（多半是拼写错误，如 `ttl-forever`、漏了 `load-` 的 `max-concurrent`）。Spring Boot 的绑定忽略不认识的键，
 * 写错的按缓存覆盖于是不生效、也没有任何提示（事故中写下的覆盖尤其如此），因此按 `cocache.caches-validation` 处理：`fail`（默认）启动失败，
 * `warn` 告警（[check]）。全局层（`cocache.defaults`、`cocache.overrides`）中不认识的键仍按 Spring Boot 的规则忽略。
 *
 * 判定接入 [CoCacheProperties] 的正式绑定（[ConfigurationPropertiesBindHandlerAdvisor]），不另做一次绑定：转换服务与
 * `@ConfigurationPropertiesBinding` 转换器因此与正式绑定相同，正式绑定接受的值不会在这里失败（#848）。绑定 `cocache` 时旁路记下
 * 绑定到了属性的配置项（Spring Boot 的 [NoUnboundElementsBindHandler]），结束时 `cocache.caches` 下其余的配置项即不认识的键。
 * 宽松命名（`localMaxAge`、`local-maxage`、环境变量 `COCACHE_CACHES_USER_LOCALMAXAGE`）与列表下标（`unlock[0]`）因此与正式绑定的规则一致。
 * 只记录，不改变绑定的结果：外层处理器的回调照常执行，其它配置类的绑定不受影响。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class UnknownCacheKeys : ConfigurationPropertiesBindHandlerAdvisor {
    @Volatile
    private var recorded: List<String> = emptyList()

    /**
     * 最近一次绑定 `cocache` 时 `cocache.caches` 下不认识的键（完整的配置键，排序）。
     */
    val keys: List<String>
        get() = recorded

    override fun apply(bindHandler: BindHandler): BindHandler = Recording(bindHandler)

    /**
     * 有不认识的键时按 [validation] 启动失败（[IllegalStateException]）或告警。
     */
    fun check(validation: CoCacheProperties.Validation) {
        val unknown = keys
        if (unknown.isEmpty()) {
            return
        }
        val message = "$CACHES sets unknown keys $unknown: misspelled, or not a per-cache key."
        check(validation == CoCacheProperties.Validation.WARN) { message }
        log.warn(message)
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
            if (context.depth != 0 || name != ROOT) {
                return
            }
            recorded = try {
                bound.onFinish(name, target, context, result)
                emptyList()
            } catch (e: UnboundConfigurationPropertiesException) {
                e.unboundProperties.map { it.name }.filter { CACHES_NAME.isAncestorOf(it) }.map { it.toString() }.sorted()
            }
        }
    }

    private companion object {
        private val log = LogFactory.getLog(UnknownCacheKeys::class.java)
        private const val CACHES = "${CoCacheProperties.PREFIX}.caches"
        private val ROOT = ConfigurationPropertyName.of(CoCacheProperties.PREFIX)
        private val CACHES_NAME = ConfigurationPropertyName.of(CACHES)
    }
}
