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

package me.ahoo.cache.api.annotation

import java.lang.annotation.Inherited

/**
 * @see me.ahoo.cache.api.client.ClientSideCache
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
@Suppress("LongParameterList")
annotation class GuavaCache(
    val initialCapacity: Int = UNSET_INT,
    val concurrencyLevel: Int = UNSET_INT,
    val maximumSize: Long = UNSET_LONG,
    val expireAfterWriteNanos: Long = UNSET_LONG,
    val expireAfterAccessNanos: Long = UNSET_LONG
) {
    companion object {
        const val UNSET_INT: Int = -1
        const val UNSET_LONG: Long = -1
    }
}
