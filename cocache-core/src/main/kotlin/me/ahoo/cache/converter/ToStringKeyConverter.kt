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
package me.ahoo.cache.converter

/**
 * To String Key Converter .
 *
 * @author ahoo wang
 */
class ToStringKeyConverter<K>(private val keyPrefix: String) : KeyConverter<K> {

    override fun toStringKey(sourceKey: K): String {
        return keyPrefix + sourceKey.toString()
    }

    override fun toString(): String {
        return "ToStringKeyConverter(keyPrefix='$keyPrefix')"
    }
}
