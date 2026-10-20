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
 * L1 中的内容本缓存读不懂，且策略为 [me.ahoo.cocache.core.policy.ForeignFormatMode.FAIL]（没有数据源的读取默认如此）。
 * 作为 [me.ahoo.cocache.api.CacheReadException] 的 `cause` 抛出。
 *
 * 常见原因是 CoCache 5.x 的数据没有迁移就升级到了 6.x：用 `CoCache5Migrator`（`cocache-redis`）迁移，
 * 或在数据可以丢弃（有数据源可回源）时把策略设为 `RECLAIM`。
 *
 * @param storageKey 存储 key
 * @param format L1 内容的格式版本：`0` 表示不是本协议写入的内容；与本节点相同表示同版本内容解码失败
 */
class ForeignFormatException(val cacheName: String, val storageKey: String, val format: Int, cause: Throwable?) :
    IllegalStateException(
        "Cache[$cacheName] - key[$storageKey] holds content this cache cannot read (format[$format]" +
            (if (format == 0) ": not written by CoCache 6, e.g. left by CoCache 5.x" else ": decoding failed") +
            "). It is kept as is: migrate it (CoCache5Migrator), or set foreignFormat = RECLAIM if a source can reload it.",
        cause
    )
