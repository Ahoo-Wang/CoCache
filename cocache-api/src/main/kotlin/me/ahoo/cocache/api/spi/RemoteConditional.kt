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

package me.ahoo.cocache.api.spi

/**
 * [RemoteStore.readIfChanged] 的结果。
 */
sealed interface RemoteConditionalRead {
    /**
     * key 中是本协议的标量值，其摘要等于 `known`：不返回正文。
     */
    data object Unchanged : RemoteConditionalRead

    /**
     * key 中是本协议的标量值，摘要不等于 `known`（或 `known` 为 `null`）。
     *
     * @param format 线格式版本：引擎只解码不高于本节点版本、不低于 1 的值，其余按 [Unreadable] 处理
     */
    class Changed(val envelope: Envelope.Scalar, val format: Int) : RemoteConditionalRead {
        override fun toString(): String = "Changed($envelope, format=$format)"
    }

    /**
     * key 中没有值：不存在、lease 占位或负缓存。
     */
    data object Absent : RemoteConditionalRead

    /**
     * key 中是不能当作本协议标量值的内容：不是本协议写入的内容（`0`）、本协议的集合（其版本）、更高版本写入的值（其版本）。
     * 不返回正文。
     */
    data class Unreadable(val format: Int) : RemoteConditionalRead
}

/**
 * [RemoteStore.putIfMatch] 的结果。
 */
sealed interface RemoteConditionalWrite {
    /**
     * 已写入并广播。
     */
    data object Written : RemoteConditionalWrite

    /**
     * 当前内容与期望不符：未修改、未广播。
     *
     * @param current 当前值的 [Digest]；`null` 表示没有值（不存在、lease 占位、负缓存，或按请求视为没有值的读不懂的内容）
     */
    data class Conflict(val current: Digest?) : RemoteConditionalWrite

    /**
     * key 中是不能当作本协议标量值的内容（同 [RemoteConditionalRead.Unreadable]），且请求没有把它视为没有值：未修改、未广播。
     */
    data class Unreadable(val format: Int) : RemoteConditionalWrite
}
