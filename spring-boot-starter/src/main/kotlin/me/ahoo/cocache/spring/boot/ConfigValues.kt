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

import me.ahoo.cocache.core.policy.Concurrency
import me.ahoo.cocache.core.policy.RefreshWindow
import me.ahoo.cocache.core.policy.Ttl
import org.springframework.boot.convert.DurationStyle
import java.time.Duration

/**
 * 配置值的解析（RFC 0005 §4.8“单位”）：时长必须带单位（`500ms`、`5s`、`2m`、`PT2M`），不带单位的值启动失败；
 * 哨兵显式：`forever`（寿命）、`off`（提前刷新窗口，`0s` 同义）、`unlimited`（回源并发上限）。
 *
 * 时长以字符串绑定再由这里解析：Spring 的时长转换会把不带单位的数字按默认单位接受。
 */
internal object ConfigValues {
    private val UNITLESS = Regex("""[+-]?\d+(\.\d+)?""")
    private const val FOREVER = "forever"
    private const val OFF = "off"
    private const val UNLIMITED = "unlimited"
    private const val REDIS_PREFIX = "${CoCacheProperties.PREFIX}.redis."
    private const val EXAMPLES = "e.g. 500ms, 5s, 2m, PT2M"

    /**
     * @param key 完整的配置键（错误信息用），如 `cocache.defaults.cache.ttl`
     */
    fun duration(key: String, raw: String): Duration {
        val text = raw.trim()
        require(!UNITLESS.matches(text)) { "$key: \"$raw\" needs a unit, e.g. ${text}${hintUnitOf(key)}, 5m, PT2M." }
        val duration = try {
            DurationStyle.detectAndParse(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$key: \"$raw\" is not a duration; write it with a unit, $EXAMPLES.", e)
        }
        require(!duration.isNegative) { "$key: \"$raw\" must not be negative." }
        return duration
    }

    /**
     * 不带单位的值的示例单位：`cocache.redis.*` 是命令级的时长，示例用毫秒；策略字段用秒。
     */
    private fun hintUnitOf(key: String): String = if (key.startsWith(REDIS_PREFIX)) "ms" else "s"

    /**
     * 正的时长（策略中除提前刷新窗口与 `ttl-jitter` 之外的时长）。
     */
    fun positiveDuration(key: String, raw: String): Duration {
        val duration = duration(key, raw)
        require(!duration.isZero) { "$key: \"$raw\" must be positive." }
        return duration
    }

    fun ttl(key: String, raw: String): Ttl =
        if (raw.trim().equals(FOREVER, ignoreCase = true)) Ttl.Forever else Ttl.Finite(positiveDuration(key, raw))

    fun refreshWindow(key: String, raw: String): RefreshWindow {
        if (raw.trim().equals(OFF, ignoreCase = true)) {
            return RefreshWindow.Off
        }
        return RefreshWindow.of(duration(key, raw).takeUnless { it.isZero })
    }

    fun concurrency(key: String, raw: String): Concurrency {
        val text = raw.trim()
        if (text.equals(UNLIMITED, ignoreCase = true)) {
            return Concurrency.Unlimited
        }
        val permits = requireNotNull(text.toIntOrNull()) {
            "$key: \"$raw\" must be a positive number of permits or unlimited."
        }
        require(permits > 0) { "$key: \"$raw\" must be positive; write unlimited for no limit." }
        return Concurrency.Max(permits)
    }
}
