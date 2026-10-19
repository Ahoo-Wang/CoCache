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

package me.ahoo.cocache.core

/**
 * 运维接口（RFC 0003 §4.14），不属于用户 API：查看统计、清空本节点的本地副本。
 */
interface CacheAdmin {
    val name: String

    val metrics: CacheMetrics

    /**
     * 本地副本数（估计值）。
     */
    val localSize: Long

    /**
     * 清空本节点的 L2（L1 是共享存储，不受影响）。正在进行的填充会因失效戳变化而被放弃。
     */
    fun clearLocal()
}
