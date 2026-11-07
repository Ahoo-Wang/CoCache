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
 * 面向用户的 key 接口只收编码后的 key（`KeyEncoder` 的输出，即存储 key 中 `{keyPrefix}{namespace}:` 之后的部分，也是 L2 的 key），
 * 存储 key 只出现在 SPI 中（P2）。运维入口（`CacheAdmin` 的按 key 操作、`ExternalInvalidator`）据此拒绝以 keyspace 的存储前缀开头的 key：
 * 那几乎总是粘贴进来的完整 Redis key，按编码 key 处理会拼出前缀重复的存储 key，操作落在没有缓存读取的槽位上，看起来成功、实际什么也没做。
 *
 * 业务 key 编码出的 key 不经这里（读写路径与事务内记录的填充接受任何编码结果）：只有运维与外部写入方手写 key 时会出这种错。
 */
internal object EncodedKeys {
    /**
     * [encodedKey] 以 [storagePrefix] 开头时以 [IllegalArgumentException] 拒绝（调用方在修改任何一层之前调用），信息给出应传的编码 key。
     *
     * @param owner 信息开头的主体：缓存为 `Cache`，`ExternalInvalidator` 为 `Namespace`
     * @param name 缓存名或命名空间
     */
    fun requireNotStorageKey(owner: String, name: String, storagePrefix: String, encodedKey: String) {
        require(!encodedKey.startsWith(storagePrefix)) {
            "$owner[$name] - key[${LosslessKeys.escape(encodedKey)}] starts with the storage prefix[$storagePrefix]: " +
                "it is a full remote key. Pass the encoded key, the part after the prefix " +
                "([${LosslessKeys.escape(encodedKey.substring(storagePrefix.length))}])."
        }
    }
}
