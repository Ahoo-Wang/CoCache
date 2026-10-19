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

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import java.util.function.Supplier

/**
 * 以 Micrometer Observation 观测回源：每次回源（或 lease 等待）一个名为 [NAME] 的 Observation，
 * 由 ObservationRegistry 上的处理器转为链路追踪的 span 与计时指标。
 *
 * 低基数标签：[CACHE]、[OPERATION]、[OUTCOME]（`success` / `error`）；高基数标签只有 [KEY_COUNT]。
 * 从不记录 key 本身（高基数，且可能是个人信息）。异常原样抛出并记录到 Observation。
 */
class MicrometerLoadObserver(private val registry: ObservationRegistry) : LoadObserver {

    @Suppress("TooGenericExceptionCaught")
    override fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T {
        if (registry.isNoop) {
            return block.get()
        }
        val observation = Observation.createNotStarted(NAME, registry)
            .contextualName("cocache ${operation.value} $cacheName")
            .lowCardinalityKeyValue(CACHE, cacheName)
            .lowCardinalityKeyValue(OPERATION, operation.value)
            .highCardinalityKeyValue(KEY_COUNT, keyCount.toString())
            .start()
        try {
            val result = observation.openScope().use { block.get() }
            observation.lowCardinalityKeyValue(OUTCOME, SUCCESS)
            return result
        } catch (e: Throwable) {
            observation.lowCardinalityKeyValue(OUTCOME, ERROR)
            observation.error(e)
            throw e
        } finally {
            observation.stop()
        }
    }

    companion object {
        /**
         * Observation 名称（计时指标名同为 `cocache.load`）。
         */
        const val NAME = "cocache.load"
        const val CACHE = "cache"
        const val OPERATION = "operation"
        const val OUTCOME = "outcome"
        const val KEY_COUNT = "key.count"
        const val SUCCESS = "success"
        const val ERROR = "error"
    }
}
