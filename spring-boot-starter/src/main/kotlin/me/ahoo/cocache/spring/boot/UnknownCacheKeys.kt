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
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.core.env.Environment

/**
 * `cocache.caches.[name]` 下不认识的配置项（多半是拼写错误，如 `ttl-forever`、漏了 `load-` 的 `max-concurrent`）。Spring Boot 的绑定忽略不认识的键，
 * 写错的按缓存覆盖于是不生效、也没有任何提示（事故中写下的覆盖尤其如此），因此按 `cocache.caches-validation` 处理：`fail`（默认）启动失败，
 * `warn` 告警。全局层（`cocache.defaults`、`cocache.overrides`）中不认识的键仍按 Spring Boot 的规则忽略。
 *
 * 判定交给 Spring Boot 自己的 [NoUnboundElementsBindHandler]：把 `cocache.caches` 再绑定一次，没有绑定到任何属性的配置项即不认识的键。
 * 宽松命名（`localMaxAge`、`local-maxage`、环境变量 `COCACHE_CACHES_USER_LOCALMAXAGE`）与列表下标（`unlock[0]`）因此与正式绑定的规则一致。
 */
internal object UnknownCacheKeys {
    private val log = LogFactory.getLog(UnknownCacheKeys::class.java)

    private const val CACHES = "${CoCacheProperties.PREFIX}.caches"

    /**
     * 有不认识的键时按 [validation] 启动失败（[IllegalStateException]）或告警。
     */
    fun check(environment: Environment, validation: CoCacheProperties.Validation) {
        val unknown = of(environment)
        if (unknown.isEmpty()) {
            return
        }
        val message = "$CACHES sets unknown keys $unknown: misspelled, or not a per-cache key."
        check(validation == CoCacheProperties.Validation.WARN) { message }
        log.warn(message)
    }

    /**
     * `cocache.caches` 下不认识的键（完整的配置键，排序）。
     */
    fun of(environment: Environment): List<String> {
        val bindable = Bindable.mapOf(String::class.java, CoCacheProperties.PolicyProperties::class.java)
        try {
            Binder.get(environment).bind(CACHES, bindable, NoUnboundElementsBindHandler(BindHandler.DEFAULT))
        } catch (e: BindException) {
            // 其余绑定错误在 CoCacheProperties 绑定时已经让启动失败，这里只会是不认识的键
            val unbound = e.cause as? UnboundConfigurationPropertiesException ?: throw e
            return unbound.unboundProperties.map { it.name.toString() }.sorted()
        }
        return emptyList()
    }
}
