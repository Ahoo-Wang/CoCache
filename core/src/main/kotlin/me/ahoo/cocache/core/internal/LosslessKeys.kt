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

package me.ahoo.cocache.core.internal

/**
 * 编码后的 key 在 L1 边界的检查：存储 key 与失效帧中的 key 都按 UTF-8 编码，含未配对 UTF-16 代理（surrogate）的
 * `String` 编不出来，会被替换为 `?`，而 L2 仍按原来的 `String` 索引。其它节点收到帧时淘汰的是另一个 key，这个 key 的
 * 本地副本陈旧到 `localMaxAge`；两个只差在这个字符上的 key 还会在 L1 中撞到同一个存储 key。
 *
 * 引擎在 key 到达 L1 之前（读取未命中、写入、运维接口）检查，[me.ahoo.cocache.core.ExternalInvalidator] 在构造存储 key 时检查。
 * L2 只由读过 L1 的路径填充，进入 L2 的 key 因此都已检查过：命中路径不检查。
 */
internal object LosslessKeys {
    /**
     * [key] 从 [from] 开始第一个未配对的 UTF-16 代理的下标；没有时为 `-1`。只在遇到代理时多做判断：其它字符只是一次范围比较。
     */
    fun unpairedSurrogateIndex(key: String, from: Int = 0): Int {
        var index = from
        while (index < key.length) {
            val char = key[index]
            if (Character.isSurrogate(char)) {
                if (!Character.isHighSurrogate(char) || index + 1 == key.length || !Character.isLowSurrogate(key[index + 1])) {
                    return index
                }
                index++
            }
            index++
        }
        return -1
    }

    /**
     * 返回 [encodedKey] 本身；含未配对的代理时以 [IllegalArgumentException] 拒绝（调用方在修改任何一层之前调用）。
     *
     * 信息给出 [owner]、[name] 与下标；key 中未配对的代理转义为 `\uXXXX`，不原样输出：原样输出的 key 在日志中同样被替换，看不出是哪个字符。
     *
     * @param owner 信息开头的主体：缓存为 `Cache`，`ExternalInvalidator` 为 `Namespace`
     * @param name 缓存名或命名空间
     */
    fun require(owner: String, name: String, encodedKey: String): String {
        val index = unpairedSurrogateIndex(encodedKey)
        require(index < 0) {
            "$owner[$name] - key[${escape(encodedKey)}] has an unpaired UTF-16 surrogate at index[$index]: " +
                "keys are encoded as UTF-8 in the remote store and in invalidation frames, which cannot represent it. " +
                "Use well-formed keys, or a KeyEncoder that encodes them losslessly."
        }
        return encodedKey
    }

    /**
     * 未配对的代理转义为 `\uXXXX`，其余字符（含配对的代理）原样保留。
     */
    fun escape(key: String): String {
        val escaped = StringBuilder(key.length)
        var start = 0
        var index = unpairedSurrogateIndex(key)
        while (index >= 0) {
            escaped.append(key, start, index).append("\\u").append("%04X".format(key[index].code))
            start = index + 1
            index = unpairedSurrogateIndex(key, start)
        }
        return escaped.append(key, start, key.length).toString()
    }
}
