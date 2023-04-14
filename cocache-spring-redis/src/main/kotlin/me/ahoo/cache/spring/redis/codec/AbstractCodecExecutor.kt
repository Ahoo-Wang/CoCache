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

package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.CacheValue

abstract class AbstractCodecExecutor<V, RAW_VALUE> : CodecExecutor<V> {

    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V> {
        val rawValue = getRawValue(key) ?: return CacheValue.missingGuard()
        return if (isMissingGuard(rawValue)) {
            CacheValue.missingGuard()
        } else {
            val value = decode(rawValue)
            CacheValue(
                value,
                ttlAt,
            )
        }
    }

    protected abstract fun getRawValue(key: String): RAW_VALUE?
    protected abstract fun isMissingGuard(rawValue: RAW_VALUE): Boolean
    protected abstract fun decode(rawValue: RAW_VALUE): V

    override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
        when {
            cacheValue.isMissingGuard -> setMissingGuard(key)
            cacheValue.isForever -> setForeverValue(key, cacheValue.value)
            else -> setValueWithTimeout(key, cacheValue)
        }
    }

    protected abstract fun setMissingGuard(key: String)
    protected abstract fun setForeverValue(key: String, value: V)
    protected abstract fun setValueWithTimeout(key: String, cacheValue: CacheValue<V>)
}
