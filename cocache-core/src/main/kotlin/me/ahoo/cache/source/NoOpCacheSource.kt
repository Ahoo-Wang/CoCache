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
package me.ahoo.cache.source

import me.ahoo.cache.CacheSource
import me.ahoo.cache.CacheValue

/**
 * No Op Cache Source .
 *
 * @author ahoo wang
 */
object NoOpCacheSource : CacheSource<Any, Any> {

    fun <K, V> noOp(): CacheSource<K, V> {
        @Suppress("UNCHECKED_CAST")
        return this as CacheSource<K, V>
    }

    override fun load(key: Any): CacheValue<Any>? {
        return null
    }

}
