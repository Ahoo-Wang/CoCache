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

package me.ahoo.cocache.api

/**
 * 读写失败的消息末尾的原因摘要：原因的类名与它的消息（没有消息时只有类名）。
 */
internal fun summaryOf(cause: Throwable): String {
    val message = cause.message
    return if (message.isNullOrBlank()) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $message"
}
