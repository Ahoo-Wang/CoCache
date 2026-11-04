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
 * L1 中的内容本缓存读不懂。读取时作为 [me.ahoo.cocache.api.CacheReadException] 的 `cause` 抛出：没有数据源的缓存读到它（有数据源的缓存
 * 回收不是本格式写入的内容与解码失败的值，绕过更高格式的内容回源），以及条件读取与 `SetCache` 读到它；条件写入、按版本写入与
 * `SetCache` 的元素增删落在它上面时，作为 [me.ahoo.cocache.api.CacheWriteException] 的 `cause` 抛出（key 不被修改）。
 *
 * 三类内容（RFC 0005 §4.7）：
 * - [format] 低于本节点：不是本格式写入的内容。格式 `0` 是不是本协议写入的内容（其它程序、外部工具，或 5.x 遗留），
 *   格式 1 是 CoCache 6.x 写入的；
 * - [newer]：更高的格式，滚动升级期间出现，或带本节点不认识的必须理解的位，既不回收也不写回；
 * - 与本节点相同：同格式的内容解码失败（或集合 key 上的标量值）。
 *
 * @param storageKey 存储 key
 * @param format L1 内容的格式版本（后端报告的）
 * @param nodeFormat 本节点的格式版本（`CoherenceBackend.format`）
 */
class ForeignFormatException(
    val cacheName: String,
    val storageKey: String,
    val format: Int,
    val nodeFormat: Int,
    cause: Throwable?,
) : IllegalStateException(message(cacheName, storageKey, format, nodeFormat), cause) {
    /**
     * 内容由更高版本的节点写入（[format] 高于本节点的版本）。
     */
    val newer: Boolean
        get() = format > nodeFormat

    private companion object {
        fun message(cacheName: String, storageKey: String, format: Int, nodeFormat: Int): String {
            val (reason, advice) = when {
                format > nodeFormat -> "written by a newer CoCache version" to "upgrade this node"
                format < nodeFormat ->
                    "not written in this node's format, e.g. by another program or CoCache 5.x or 6.x" to
                        "delete it if it can be dropped"
                else -> "decoding failed" to "delete it if it can be dropped"
            }
            return "Cache[$cacheName] - key[$storageKey] holds content this cache cannot read " +
                "(format[$format], this node writes format[$nodeFormat]: $reason). It is kept as is: $advice."
        }
    }
}
